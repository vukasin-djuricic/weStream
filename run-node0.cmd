@echo off
REM Node 0 = the SEED (and DHT bootstrap). Double-click to launch full speed.
REM Optional throttle for the sliding-window demo:  run-node0.cmd 1500
call "%~dp0_launch-node.cmd" 0 %*
