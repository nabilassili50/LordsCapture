#!/usr/bin/env python3
"""scan_client_v1.py - test loop for the LordsCapture scan server (127.0.0.1:8765).
Asks for the next map spot, waits, reports it done. goto() is still a stub:
it prints the spot; the real map-jump tap sequence comes after the game's coordinate dialog is calibrated."""
import json, sys, time, urllib.request

BASE = "http://127.0.0.1:8765"
WAIT = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0   # seconds to let the game send the map packets

def get(path):
    with urllib.request.urlopen(BASE + path, timeout=5) as r:
        return json.loads(r.read().decode("utf-8"))

def goto(x, y):
    print(f"-> go to X:{x} Y:{y}")   # TODO: open coordinate dialog, type X/Y, press go

while True:
    try:
        t = get("/next")
        if not t.get("ok"):
            print("nothing due, waiting...")
            time.sleep(5)
            continue
        goto(t["x"], t["y"])
        time.sleep(WAIT)
        get(f"/done?x={t['x']}&y={t['y']}")
        print(get("/status"))
    except Exception as e:
        print("server not reachable (start the capture in LordsCapture):", e)
        time.sleep(5)
