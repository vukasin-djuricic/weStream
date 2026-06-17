@echo off
REM Node 0 as a THROTTLED seed (1500 KB/s ~ 1.5 MB/s) so the sliding-window
REM behaviour is visible. Lower the number for an even slower, clearer demo.
call "%~dp0_launch-node.cmd" 0 1500
