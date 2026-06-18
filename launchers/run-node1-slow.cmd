@echo off
REM Node 1 as a THROTTLED seed (3500 KB/s) — share the SAME file as node 0 so the
REM swarm has two capped seeds; the leecher then visibly pulls from both at once.
call "%~dp0_launch-node.cmd" 1 3500
