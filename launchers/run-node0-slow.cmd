@echo off
REM Node 0 as a THROTTLED seed (3500 KB/s) so the sliding-window behaviour is
REM visible. Lower the number for an even slower, clearer demo.
call "%~dp0_launch-node.cmd" 0 3500
