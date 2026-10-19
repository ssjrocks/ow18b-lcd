"""Talking to the meter over Bluetooth LE (bleak), in a background thread with its own asyncio loop."""
from __future__ import annotations

import asyncio
import json
import logging
import threading
from pathlib import Path
from typing import Callable

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


class Meter:
    """Calls on_reading(Reading) and on_status(str, connected) from the background thread."""

    def __init__(self, on_reading: Callable[[protocol.Reading], None],
                 on_status: Callable[[str, bool], None], demo: bool = False):
        self.on_reading, self.on_status, self.demo = on_reading, on_status, demo
        self._loop = asyncio.new_event_loop()
        self._stop: asyncio.Event | None = None
        self._thread = threading.Thread(target=self._run, name="meter", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        if self._stop is not None:
            self._loop.call_soon_threadsafe(self._stop.set)
        self._thread.join(timeout=5)

    def _run(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._stop = asyncio.Event()
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
        try:
            await asyncio.wait_for(self._stop.wait(), seconds)
        except asyncio.TimeoutError:
            pass

    async def _demo(self) -> None:
        self.on_status("Demo (recorded readings)", True)
        while not self._stop.is_set():
            for hexs, seconds in DEMO:
                for _ in range(seconds * 2):
                    if r := protocol.decode(bytes.fromhex(hexs)):
                        self.on_reading(r)
                    await self._sleep(0.5)
                    if self._stop.is_set():
                        return

    async def _find(self) -> str | None:
        cfg = load_config()
        known = cfg.get("address")
        device = await BleakScanner.find_device_by_filter(
            lambda d, ad: (d.address == known) or (d.name or ad.local_name) == protocol.NAME, timeout=10)
        if device is None:
            return None
        if device.address != known:
            save_config({**cfg, "address": device.address})
        return device.address

    async def _connect_forever(self) -> None:
        while not self._stop.is_set():
            self.on_status("Looking for the meter…", False)
            try:
                address = await self._find()
                if address is None:
                    self.on_status("Meter not found; is it on with Bluetooth enabled?", False)
                    await self._sleep(3)
                    continue
                self.on_status("Connecting…", False)
                gone = asyncio.Event()
                async with BleakClient(address, timeout=20, disconnected_callback=lambda _: gone.set()) as client:
                    def notified(_, data: bytearray) -> None:
                        for packet in protocol.split(bytes(data)):
                            if r := protocol.decode(packet):
                                self.on_reading(r)

                    await client.start_notify(protocol.NOTIFY, notified)
                    self.on_status(f"Connected to {address}", True)
                    stop = asyncio.ensure_future(self._stop.wait())
                    lost = asyncio.ensure_future(gone.wait())
                    await asyncio.wait({stop, lost}, return_when=asyncio.FIRST_COMPLETED)
                    stop.cancel(), lost.cancel()
                self.on_status("Disconnected", False)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # BlueZ errors come in many types; keep retrying
                log.info("connection failed: %s", e)
                self.on_status(f"Connection failed: {e}", False)
                await self._sleep(3)
