#property strict
#property script_show_inputs
// Experimental read-only observer. Run in a live terminal, never Strategy Tester.
input int DurationSeconds=10;
input int PollMilliseconds=20;

void OnStart()
{
   if(MQLInfoInteger(MQL_TESTER) || DurationSeconds<1 || DurationSeconds>10 || PollMilliseconds<1)
   { Print("Invalid parameters or tester context"); return; }
   string symbol="WINZ26";
   string filename=StringFormat("WINZ26-native-clock-%I64u.tsv",GetTickCount64());
   if(FileIsExist(filename)) { Print("Output exists; refusing overwrite"); return; }
   int file=FileOpen(filename,FILE_WRITE|FILE_CSV|FILE_ANSI,'\t',CP_UTF8);
   if(file==INVALID_HANDLE) { PrintFormat("FileOpen failed: %d",GetLastError()); return; }
   FileWrite(file,"sequence","symbol","server","terminal_data_path","terminal_build",
      "monotonic_start_ms","monotonic_end_ms","time_gmt_before","time_gmt_after",
      "time_current","time_trade_server","time_local","ok","error",
      "time","time_msc","bid","ask","last","volume","volume_real","flags");
   ulong start=GetTickCount64();
   int sequence=0;
   while(!IsStopped() && GetTickCount64()-start<(ulong)DurationSeconds*1000)
   {
      MqlTick tick={};
      ulong before=GetTickCount64();
      datetime gmt_before=TimeGMT();
      ResetLastError();
      bool ok=SymbolInfoTick(symbol,tick);
      int error=ok ? 0 : GetLastError();
      ulong after=GetTickCount64();
      datetime gmt_after=TimeGMT();
      FileWrite(file,++sequence,symbol,AccountInfoString(ACCOUNT_SERVER),
         TerminalInfoString(TERMINAL_DATA_PATH),TerminalInfoInteger(TERMINAL_BUILD),
         before,after,(long)gmt_before,(long)gmt_after,(long)TimeCurrent(),
         (long)TimeTradeServer(),(long)TimeLocal(),ok ? 1 : 0,error,
         (long)tick.time,tick.time_msc,DoubleToString(tick.bid,16),DoubleToString(tick.ask,16),
         DoubleToString(tick.last,16),tick.volume,DoubleToString(tick.volume_real,16),tick.flags);
      FileFlush(file);
      Sleep(PollMilliseconds);
   }
   FileClose(file);
   Print("Diagnostic saved: ",TerminalInfoString(TERMINAL_DATA_PATH),"\\MQL5\\Files\\",filename);
}
