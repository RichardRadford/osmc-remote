#!/usr/bin/env python3
# Sends one JSON-RPC request (read from stdin) to Kodi's local TCP interface
# (127.0.0.1:9090) and prints the matching response. Exit 2 = no response.
import json
import socket
import sys

req = sys.stdin.read()
want = json.loads(req).get("id")
try:
    s = socket.create_connection(("127.0.0.1", 9090), 3)
except OSError as e:
    sys.stderr.write("connect failed: %s\n" % e)
    sys.exit(2)
s.settimeout(8)
s.sendall(req.encode("utf-8"))
dec = json.JSONDecoder()
buf = ""
try:
    while True:
        data = s.recv(65536)
        if not data:
            break
        buf += data.decode("utf-8", "replace")
        while True:
            buf = buf.lstrip()
            if not buf:
                break
            try:
                obj, end = dec.raw_decode(buf)
            except ValueError:
                break  # incomplete object, read more
            buf = buf[end:]
            # skip notifications; return the response to our request
            if isinstance(obj, dict) and obj.get("id") == want:
                sys.stdout.write(json.dumps(obj))
                sys.exit(0)
except socket.timeout:
    pass
sys.stderr.write("no response from Kodi\n")
sys.exit(2)
