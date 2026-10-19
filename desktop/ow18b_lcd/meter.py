"""Talking to the meter over Bluetooth LE (bleak), in a background thread with its own asyncio loop.

Callbacks (on_reading, on_status, on_info) and command completions run in that thread; the GTK app
hands them to its main loop with GLib.idle_add.
"""
from __future__ import annotations

import asyncio
import datetime as dt
import json
import logging
import threading
from concurrent.futures import Future
from pathlib import Path
from typing import Any, Callable

from bleak import BleakClient, BleakScanner

from . import protocol

log = logging.getLogger(__name__)
CONFIG = Path.home() / ".config" / "ow18b-lcd" / "config.json"

# Packets from a real OW18B going through its dial positions, for --demo.
DEMO = [
    ("19 f0 04 00 00 00", 6), ("1a f0 04 00 74 00", 1), ("1a f0 04 00 57 00", 1), ("1a f0 04 00 41 00", 1),
    ("1a f0 04 00 5b 00", 1), ("1a f0 04 00 7c 00", 1), ("1a f0 04 00 36 00", 1), ("1a f0 04 00 d7 82", 1),
    ("1a f0 04 00 30 80", 1), ("1a f0 04 00 d6 00", 1), ("1a f0 04 00 2f 00", 1), ("1a f0 04 00 a0 00", 1),
    ("1a f0 04 00 88 00", 1), ("1a f0 04 00 2c 00", 1), ("1a f0 04 00 29 80", 1), ("1a f0 04 00 cd 00", 2),
    ("1a f0 04 00 c7 00", 1), ("1a f0 04 00 16 00", 1), ("1a f0 04 00 2e 00", 3),
    ("27 f1 04 00 ff 7f", 2), ("2f f1 04 00 ff 7f", 3), ("37 f1 04 00 ff 7f", 3),
    ("4a f1 04 00 00 00", 4), ("a3 f1 04 00 00 00", 4), ("20 f2 00 00 1a 00", 4),
    ("60 f3 00 00 00 00", 3), ("60 f3 00 00 01 00", 1), ("60 f3 00 00 02 00", 1), ("60 f3 00 00 03 00", 2),
    ("60 f3 00 00 01 00", 1), ("60 f3 00 00 00 00", 2), ("9a f0 04 00 08 00", 3), ("91 f0 04 00 01 00", 3),
    ("a2 f0 00 00 00 00", 3),
]
DEMO_INFO = protocol.DeviceInfo(18, 87, "3.0.4", False)


def load_config() -> dict:
    try:
        return json.loads(CONFIG.read_text())
    except (OSError, ValueError):
        return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG.parent.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(json.dumps(cfg, indent=2))
    except OSError as e:
        log.warning("couldn't save %s: %s", CONFIG, e)


def update_config(**changes: Any) -> None:
    save_config({**load_config(), **changes})


class NotConnected(Exception):
    def __str__(self) -> str:
        return "The meter isn't connected"


class Meter:
    def __init__(self, on_reading: Callable[[protocol.Reading], None],
                 on_status: Callable[[str, bool], None],
                 on_info: Callable[[protocol.DeviceInfo], None] = lambda i: None,
                 demo: bool = False):
        self.on_reading, self.on_status, self.on_info, self.demo = on_reading, on_status, on_info, demo
        self._loop = asyncio.new_event_loop()
        self._stop: asyncio.Event | None = None
        self._wake: asyncio.Event | None = None  # cuts a retry delay short
        self._thread = threading.Thread(target=self._run, name="meter", daemon=True)
        self._client: BleakClient | None = None
        self._dump: bytearray | None = None
        self._dump_done: asyncio.Event | None = None
        self.paused = False  # the user disconnected: don't reconnect
        self.info: protocol.DeviceInfo | None = None
        self.address: str | None = load_config().get("address")

    # --- lifecycle ---

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        if self._stop is not None and self._thread.is_alive():
            self._loop.call_soon_threadsafe(self._stop.set)
        self._thread.join(timeout=5)

    def _run(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._stop = asyncio.Event()
        self._wake = asyncio.Event()
        main = self._demo() if self.demo else self._connect_forever()
        task = self._loop.create_task(main)
        self._loop.run_until_complete(self._stop.wait())
        task.cancel()
        try:
            self._loop.run_until_complete(task)
        except (asyncio.CancelledError, Exception):
            pass
        self._loop.close()

    async def _sleep(self, seconds: float) -> None:
        """Waits, or returns early on stop or when something changed (_wake)."""
        self._wake.clear()
        waits = {asyncio.ensure_future(self._wake.wait()), asyncio.ensure_future(self._stop.wait())}
        _, pending = await asyncio.wait(waits, timeout=seconds, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()

    def _call(self, coro_fn: Callable[[], Any]) -> Future:
        """Runs a coroutine on the meter's loop; the Future completes in that thread."""
        return asyncio.run_coroutine_threadsafe(coro_fn(), self._loop)

    # --- connection control (call from any thread) ---

    def set_paused(self, paused: bool) -> None:
        """Disconnect and stay disconnected (True), or reconnect (False)."""
        def apply():
            self.paused = paused
            self._wake.set()
            if paused and self._client is not None:
                asyncio.ensure_future(self._client.disconnect())
        self._loop.call_soon_threadsafe(apply)

    def use_address(self, address: str) -> None:
        """Switch to another meter."""
        def apply():
            self.address = address
            update_config(address=address)
            self.paused = False
            self._wake.set()
            if self._client is not None and self._client.address != address:
                asyncio.ensure_future(self._client.disconnect())
        self._loop.call_soon_threadsafe(apply)

    def scan(self, seconds: float = 6) -> Future:
        """Future -> [(address, name, rssi)] of nearby named devices, meters first."""
        async def go():
            if self.demo:
                return [("00:00:00:00:00:00", "BDM (demo)", -50)]
            found = await BleakScanner.discover(timeout=seconds, return_adv=True)
            out = []
            for address, (d, ad) in found.items():
                name = ad.local_name or d.name
                if name:
                    out.append((address, name, ad.rssi))
            if self._client is not None and self.address and all(a != self.address for a, _, _ in out):
                out.append((self.address, self.name, 0))  # a connected meter doesn't advertise
            known = set(load_config().get("known", []))
            out.sort(key=lambda t: (t[1] != protocol.NAME and t[0] not in known, -t[2]))
            return out
        return self._call(go)

    @property
    def name(self) -> str:
        return load_config().get("names", {}).get(self.address or "", protocol.NAME)

    # --- commands (call from any thread; each returns a concurrent Future) ---

    def press(self, key: int, press: int = protocol.SHORT) -> Future:
        async def go():
            if not self.demo:
                await self._write(protocol.KEYS, protocol.key(key, press))
        return self._call(go)

    def read_info(self) -> Future:
        return self._call(self._read_info)

    async def _read_info(self) -> protocol.DeviceInfo:
        if self.demo:
            return DEMO_INFO
        self.info = protocol.parse_info(bytes(await self._need().read_gatt_char(protocol.INFO)))
        self.on_info(self.info)
        return self.info

    def rename(self, name: str) -> Future:
        data = protocol.rename(name)  # raises ValueError for a bad name before anything is sent

        async def go():
            if not self.demo:
                await self._write(protocol.COMMAND, data)
            names = load_config().get("names", {})
            update_config(names={**names, self.address or "demo": name})
        return self._call(go)

    def start_recording(self, interval: int, count: int) -> Future:
        """Sets the meter's clock and starts an offline log; the meter then turns Bluetooth off."""
        data = protocol.start_record(interval, count)

        async def go():
            if not self.demo:
                await self._write(protocol.COMMAND, protocol.set_clock(dt.datetime.now()))
                await asyncio.sleep(0.2)
                await self._write(protocol.COMMAND, data)
            update_config(recording={"started": dt.datetime.now().isoformat(timespec="seconds"),
                                     "interval": interval, "count": count})
        return self._call(go)

    def stop_recording(self) -> Future:
        async def go():
            if not self.demo:
                await self._write(protocol.COMMAND, protocol.STOP_RECORD)
                await asyncio.sleep(0.5)
                await self._read_info()
        return self._call(go)

    def read_log(self, progress: Callable[[int], None] = lambda n: None) -> Future:
        """Future -> protocol.OfflineLog read from the meter's memory."""
        async def go():
            if self.demo:
                return _demo_log()
            c = self._need()
            info = self.info or protocol.parse_info(bytes(await c.read_gatt_char(protocol.INFO)))
            dated = info.firmware_number >= 12
            self._dump, self._dump_done = bytearray(), asyncio.Event()
            try:
                await self._write(protocol.COMMAND, protocol.READ_LOG if dated else protocol.READ_LOG_OLD)
                last = -1
                while True:
                    try:
                        await asyncio.wait_for(self._dump_done.wait(), 5)
                        break
                    except asyncio.TimeoutError:
                        if len(self._dump) == last:
                            raise TimeoutError("The meter stopped sending its log") from None
                        last = len(self._dump)
                        progress(last)
                return protocol.parse_dump(bytes(self._dump), dated)
            finally:
                self._dump = None
        return self._call(go)

    def _need(self) -> BleakClient:
        if self._client is None or not self._client.is_connected:
            raise NotConnected()
        return self._client

    async def _write(self, char: str, data: bytes) -> None:
        await self._need().write_gatt_char(char, data, response=True)

    # --- the connection loop ---

    async def _demo(self) -> None:
        self.on_status("Demo (recorded readings)", True)
        self.info = DEMO_INFO
        self.on_info(DEMO_INFO)
        while not self._stop.is_set():
            for hexs, seconds in DEMO:
                for _ in range(seconds * 2):
                    if r := protocol.decode(bytes.fromhex(hexs)):
                        self.on_reading(r)
                    await asyncio.sleep(0.5)
                    if self._stop.is_set():
                        return

    async def _find(self) -> str | None:
        known = self.address
        device = await BleakScanner.find_device_by_filter(
            lambda d, ad: (d.address == known) if known else (d.name or ad.local_name) == protocol.NAME, timeout=10)
        if device is None:
            return None
        if device.address != known:
            self.address = device.address
            update_config(address=device.address)
        known_list = load_config().get("known", [])
        if device.address not in known_list:
            update_config(known=[*known_list, device.address])
        return device.address

    def _notified(self, _, data: bytearray) -> None:
        if self._dump is not None:
            self._dump.extend(data)
            if protocol.dump_complete(bytes(self._dump)):
                self._loop.call_soon_threadsafe(self._dump_done.set)
            return
        for packet in protocol.split(bytes(data)):
            if r := protocol.decode(packet):
                self.on_reading(r)

    async def _connect_forever(self) -> None:
        while not self._stop.is_set():
            if self.paused:
                self.on_status("Disconnected", False)
                await self._sleep(3600)
                continue
            self.on_status("Looking for the meter…", False)
            try:
                address = await self._find()
                if self.paused:
                    continue
                if address is None:
                    self.on_status("Meter not found. Is it on, with Bluetooth enabled?", False)
                    await self._sleep(3)
                    continue
                self.on_status("Connecting…", False)
                gone = asyncio.Event()
                async with BleakClient(address, timeout=20, disconnected_callback=lambda _: gone.set()) as client:
                    self._client = client
                    try:
                        self.info = protocol.parse_info(bytes(await client.read_gatt_char(protocol.INFO)))
                    except Exception as e:  # not fatal: readings still work
                        log.info("couldn't read device info: %s", e)
                        self.info = None
                    await client.start_notify(protocol.NOTIFY, self._notified)
                    self.on_status(f"Connected to {self.name}", True)
                    if self.info:
                        self.on_info(self.info)
                    stop = asyncio.ensure_future(self._stop.wait())
                    lost = asyncio.ensure_future(gone.wait())
                    await asyncio.wait({stop, lost}, return_when=asyncio.FIRST_COMPLETED)
                    stop.cancel(), lost.cancel()
                self._client = None
                self.on_status("Disconnected", False)
                await self._sleep(1)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # BlueZ errors come in many types; keep retrying
                self._client = None
                log.info("connection failed: %s", e)
                self.on_status(f"Connection failed: {e}", False)
                await self._sleep(3)


def _demo_log() -> protocol.OfflineLog:
    import math
    lg = protocol.OfflineLog(dt.datetime.now().replace(microsecond=0) - dt.timedelta(minutes=10), 5)
    for i in range(120):
        lg.readings.append((i * 5, protocol.Reading(0, 4, 3, int(1500 + 300 * math.sin(i / 9)))))
    return lg
