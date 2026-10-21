#property strict
#property version "1.02"
#property description "Experimental read-only WINZ26 event DOM and raw tick capture. No trading."

input int DurationSeconds=30;
input int TimerMilliseconds=20;
const string SYMBOL="WINZ26";
const int QUEUE_CAPACITY=1024;
const int WRITE_PER_TIMER=64;
const int TICK_BATCH=2048;
const int RECONFIRM_MAX_ATTEMPTS=3;
const ulong RECONFIRM_BUDGET_US=500000;
const int CURSOR_DIAGNOSTIC_MAX_ROWS=2048; // Per array; includes the entire current batch/prefix.
const int CURSOR_DIAGNOSTIC_MAX_CHARS=1048576; // Complete diagnostic line, excluding newline.

class CObservation
{
public:
   string kind,trigger;
   string continuity_json;
   ulong sequence,handler_start,handler_end,query_start,query_end;
   datetime local_before,local_after,gmt_before,gmt_after,server_time;
   bool ok;
   int error,returned;
   long cursor_before,cursor_after;
   ulong ordinal_before,ordinal_after;
   MqlBookInfo book[];
   MqlTick ticks[];
   ulong ordinals[];
   bool cursor_diagnostic,diagnostic_allocation_failed;
   int mismatch_row,mismatch_prefix,diagnostic_expected_total,diagnostic_returned_available;
   MqlTick diagnostic_expected[],diagnostic_returned[];
   MqlTick mismatch_expected,mismatch_received;
   CObservation()
   {
      sequence=0;handler_start=0;handler_end=0;query_start=0;query_end=0;
      local_before=0;local_after=0;gmt_before=0;gmt_after=0;server_time=0;
      ok=true;error=0;returned=0;cursor_before=0;cursor_after=0;ordinal_before=0;ordinal_after=0;
      cursor_diagnostic=false;diagnostic_allocation_failed=false;
      mismatch_row=-1;mismatch_prefix=-1;diagnostic_expected_total=0;diagnostic_returned_available=0;
   }
};

CObservation *queue[1024];
int head=0,queued=0,peak_queue=0,file=INVALID_HANDLE;
bool subscribed=false,pending_ticks=true,stopping=false,io_failed=false,completed_window=false;
int subscription_error=0,timer_error=0;
bool initialized=false,output_created=false;
int init_result=INIT_FAILED,init_error=0,file_open_error=0,write_error=0;
ulong global_sequence=0,start_us=0,last_tick_query=0,last_flush=0;
ulong book_events=0,tick_events=0,timer_events=0,dropped=0,raw_ticks=0,trades=0,quotes=0;
ulong full_batches=0,empty_books=0,query_errors=0;
long cursor_ms=0;
ulong cursor_ordinal=0;
string fault="",filename="";
MqlTick consumed_prefix[2048];
int consumed_prefix_count=0;
bool waiting_prefix=false;
int reconfirm_attempts=0;
ulong recovery_id=0,uncertainty_start=0,recovery_first_sequence=0,last_verified_us=0;
ulong recovery_last_verified_us=0,recovery_empty_responses=0,recovery_total_attempts=0;
ulong recovery_reconfirmed=0,recovery_unverified=0,recovery_duration_total=0,recovery_duration_max=0;
string last_recovery_json="null";
bool cursor_diagnostic_captured=false; // At most one terminal-failure diagnostic per session.

string DeinitReasonName(int reason)
{
   switch(reason)
   {
      case REASON_PROGRAM: return "REASON_PROGRAM";
      case REASON_REMOVE: return "REASON_REMOVE";
      case REASON_RECOMPILE: return "REASON_RECOMPILE";
      case REASON_CHARTCHANGE: return "REASON_CHARTCHANGE";
      case REASON_CHARTCLOSE: return "REASON_CHARTCLOSE";
      case REASON_PARAMETERS: return "REASON_PARAMETERS";
      case REASON_ACCOUNT: return "REASON_ACCOUNT";
      case REASON_TEMPLATE: return "REASON_TEMPLATE";
      case REASON_INITFAILED: return "REASON_INITFAILED";
      case REASON_CLOSE: return "REASON_CLOSE";
   }
   return "UNKNOWN_REASON";
}

string OutputPath()
{
   if(filename=="") return "(filename not generated)";
   return TerminalInfoString(TERMINAL_DATA_PATH)+"\\MQL5\\Files\\"+filename;
}

int InitFailure(string cause,int result,int error=0)
{
   fault=cause;init_result=result;init_error=error;
   PrintFormat("Experimental initialization failed: cause=%s init_result=%d error=%d DurationSeconds=%d TimerMilliseconds=%d symbol=%s output_created=%s path=%s",
      fault,init_result,init_error,DurationSeconds,TimerMilliseconds,_Symbol,
      output_created ? "true" : "false",OutputPath());
   return result;
}

string U(ulong x) { return StringFormat("%I64u",x); }
string L(long x) { return StringFormat("%I64d",x); }
string B(bool x) { return x ? "true" : "false"; }
string D(double x)
{
   if(MathIsValidNumber(x)) return DoubleToString(x,16);
   return "{\"non_finite\":\""+DoubleToString(x,16)+"\"}";
}
string J(string x)
{
   StringReplace(x,"\\","\\\\");StringReplace(x,"\"","\\\"");
   StringReplace(x,"\r","\\r");StringReplace(x,"\n","\\n");StringReplace(x,"\t","\\t");
   return "\""+x+"\"";
}

bool WriteLine(string text)
{
   if(file==INVALID_HANDLE || io_failed) return false;
   ResetLastError();
   uint wrote=FileWriteString(file,text+"\n");
   int error=GetLastError();
   if(wrote==0 || error!=0)
   {
      write_error=error;io_failed=true;fault="FILE_WRITE_FAILED";
      PrintFormat("Experimental file write failed: error=%d wrote=%u path=%s",error,wrote,OutputPath());
      return false;
   }
   return true;
}

CObservation *NewObservation(string kind,string trigger)
{
   if(queued>=QUEUE_CAPACITY)
   { dropped++;fault="OBSERVATION_QUEUE_OVERFLOW";return NULL; }
   CObservation *o=new CObservation;
   if(o==NULL) { dropped++;fault="ALLOCATION_FAILED";return NULL; }
   o.kind=kind;o.trigger=trigger;o.sequence=++global_sequence;
   o.handler_start=GetMicrosecondCount();
   return o;
}

void Enqueue(CObservation *o)
{
   if(o==NULL) return;
   queue[(head+queued)%QUEUE_CAPACITY]=o;queued++;
   peak_queue=MathMax(peak_queue,queued);
}

string BookRow(MqlBookInfo &r,int index)
{
   return "{\"source_index\":"+IntegerToString(index)+",\"type\":"+IntegerToString((int)r.type)+
      ",\"price\":"+D(r.price)+",\"volume\":"+L(r.volume)+",\"volume_dbl\":"+D(r.volume_real)+"}";
}

// Ranking is done only by the timer writer, never inside OnBookEvent.
string BestSide(MqlBookInfo &book[],ENUM_BOOK_TYPE type)
{
   int best[10];int count=0;
   for(int i=0;i<ArraySize(book);i++)
   {
      if(book[i].type!=type) continue;
      int position=count;
      for(int j=0;j<count;j++)
         if((type==BOOK_TYPE_BUY && book[i].price>book[best[j]].price) ||
            (type==BOOK_TYPE_SELL && book[i].price<book[best[j]].price)) { position=j;break; }
      if(position>=10) continue;
      for(int j=MathMin(count,9);j>position;j--) best[j]=best[j-1];
      best[position]=i;if(count<10) count++;
   }
   string result="[";
   for(int i=0;i<count;i++) { if(i>0) result+=",";result+=BookRow(book[best[i]],best[i]); }
   return result+"]";
}

string TickRow(MqlTick &t,ulong ordinal)
{
   bool trade=(t.flags & (TICK_FLAG_LAST|TICK_FLAG_VOLUME))!=0;
   bool buy=(t.flags&TICK_FLAG_BUY)!=0,sell=(t.flags&TICK_FLAG_SELL)!=0;
   string side=trade ? J(buy==sell ? "AMBIGUOUS" : buy ? "BUY" : "SELL") : "null";
   uint known=TICK_FLAG_BID|TICK_FLAG_ASK|TICK_FLAG_LAST|TICK_FLAG_VOLUME|TICK_FLAG_BUY|TICK_FLAG_SELL;
   return "{\"time\":"+L((long)t.time)+",\"time_msc\":"+L(t.time_msc)+
      ",\"bid\":"+D(t.bid)+",\"ask\":"+D(t.ask)+",\"last\":"+D(t.last)+
      ",\"volume\":"+U(t.volume)+",\"volume_real\":"+D(t.volume_real)+
      ",\"flags\":"+IntegerToString(t.flags)+",\"unknown_flag_mask\":"+U(t.flags & ~known)+
      ",\"cursor_ordinal\":"+U(ordinal)+",\"classification\":"+J(trade ? "trade" : "quote")+
      ",\"side\":"+side+"}";
}

// Diagnostic-only serialization: no classifications, accepted ordinals or TickRow calls.
string DiagnosticDouble(double x)
{
   if(MathIsValidNumber(x)) return StringFormat("%.17g",x);
   return "{\"non_finite\":"+J(DoubleToString(x,16))+"}";
}

string DiagnosticTick(const MqlTick &t)
{
   return "{\"time\":"+L((long)t.time)+",\"time_msc\":"+L(t.time_msc)+
      ",\"bid\":"+DiagnosticDouble(t.bid)+",\"ask\":"+DiagnosticDouble(t.ask)+
      ",\"last\":"+DiagnosticDouble(t.last)+",\"volume\":"+U(t.volume)+
      ",\"volume_real\":"+DiagnosticDouble(t.volume_real)+",\"flags\":"+U(t.flags)+"}";
}

string DifferentTickFields(const MqlTick &a,const MqlTick &b)
{
   string fields="";
   if(a.time!=b.time) fields+=",\"time\"";
   if(a.time_msc!=b.time_msc) fields+=",\"time_msc\"";
   if(!MathIsValidNumber(a.bid) || !MathIsValidNumber(b.bid) || a.bid!=b.bid) fields+=",\"bid\"";
   if(!MathIsValidNumber(a.ask) || !MathIsValidNumber(b.ask) || a.ask!=b.ask) fields+=",\"ask\"";
   if(!MathIsValidNumber(a.last) || !MathIsValidNumber(b.last) || a.last!=b.last) fields+=",\"last\"";
   if(a.volume!=b.volume) fields+=",\"volume\"";
   if(!MathIsValidNumber(a.volume_real) || !MathIsValidNumber(b.volume_real) || a.volume_real!=b.volume_real) fields+=",\"volume_real\"";
   if(a.flags!=b.flags) fields+=",\"flags\"";
   return "["+(fields=="" ? "" : StringSubstr(fields,1))+"]";
}

void CaptureCursorDiagnostic(CObservation *o,MqlTick &rows[],int row_index,int prefix_index)
{
   if(cursor_diagnostic_captured) return;
   cursor_diagnostic_captured=true;o.cursor_diagnostic=true;
   o.mismatch_row=row_index;o.mismatch_prefix=prefix_index;
   o.mismatch_expected=consumed_prefix[prefix_index];o.mismatch_received=rows[row_index];
   o.diagnostic_expected_total=consumed_prefix_count;
   o.diagnostic_returned_available=ArraySize(rows);
   int expected=MathMin(consumed_prefix_count,CURSOR_DIAGNOSTIC_MAX_ROWS);
   int received=MathMin(MathMin(o.returned,ArraySize(rows)),CURSOR_DIAGNOSTIC_MAX_ROWS);
   if(ArrayResize(o.diagnostic_expected,expected)==expected)
      for(int i=0;i<expected;i++) o.diagnostic_expected[i]=consumed_prefix[i];
   else { o.diagnostic_allocation_failed=true;ArrayResize(o.diagnostic_expected,0); }
   if(ArrayResize(o.diagnostic_returned,received)==received)
      for(int i=0;i<received;i++) o.diagnostic_returned[i]=rows[i];
   else { o.diagnostic_allocation_failed=true;ArrayResize(o.diagnostic_returned,0); }
   // No additional queue allocation, fault replacement, cursor update or accepted counters.
}

void WriteCursorDiagnostic(CObservation *o)
{
   if(!o.cursor_diagnostic || io_failed) return;
   ulong begin=GetMicrosecondCount();
   string expected="[",received="[";
   int expected_written=0,received_written=0;
   // Reserve 4096 characters for bounded metadata and the first differing pair.
   int budget=CURSOR_DIAGNOSTIC_MAX_CHARS-4096;
   for(int i=0;i<ArraySize(o.diagnostic_expected);i++)
   {
      string row=(i>0 ? "," : "")+DiagnosticTick(o.diagnostic_expected[i]);
      if(StringLen(expected)+StringLen(received)+StringLen(row)>budget) break;
      expected+=row;expected_written++;
   }
   for(int i=0;i<ArraySize(o.diagnostic_returned);i++)
   {
      string row=(i>0 ? "," : "")+DiagnosticTick(o.diagnostic_returned[i]);
      if(StringLen(expected)+StringLen(received)+StringLen(row)>budget) break;
      received+=row;received_written++;
   }
   bool truncated=expected_written<o.diagnostic_expected_total || received_written<o.returned;
   string text="{\"kind\":\"cursor_prefix_mismatch_diagnostic\",\"diagnostic_schema\":1,\"symbol\":"+J(SYMBOL)+
      ",\"parent_sequence\":"+U(o.sequence)+",\"diagnostic_only\":true,\"rows_are_new\":false,\"accepted_rows\":0"+
      ",\"reason\":\"CURSOR_PREFIX_CONTENT_MISMATCH\",\"cursor_before\":{\"time_msc\":"+L(o.cursor_before)+
      ",\"ordinal\":"+U(o.ordinal_before)+"},\"query_start_us\":"+U(o.query_start)+
      ",\"query_end_us\":"+U(o.query_end)+",\"query_duration_us\":"+U(o.query_end-o.query_start)+
      ",\"returned_rows\":"+IntegerToString(o.returned)+",\"returned_array_size\":"+IntegerToString(o.diagnostic_returned_available)+
      ",\"expected_prefix_total\":"+IntegerToString(o.diagnostic_expected_total)+
      ",\"first_response_index\":"+IntegerToString(o.mismatch_row)+
      ",\"first_prefix_index\":"+IntegerToString(o.mismatch_prefix)+
      ",\"different_fields\":"+DifferentTickFields(o.mismatch_expected,o.mismatch_received)+
      ",\"first_expected\":"+DiagnosticTick(o.mismatch_expected)+",\"first_received\":"+DiagnosticTick(o.mismatch_received)+
      ",\"max_rows_per_array\":"+IntegerToString(CURSOR_DIAGNOSTIC_MAX_ROWS)+
      ",\"max_record_chars\":"+IntegerToString(CURSOR_DIAGNOSTIC_MAX_CHARS)+
      ",\"expected_prefix_written\":"+IntegerToString(expected_written)+
      ",\"returned_rows_written\":"+IntegerToString(received_written)+
      ",\"truncated\":"+B(truncated)+",\"allocation_failed\":"+B(o.diagnostic_allocation_failed)+
      ",\"row_limit_reached\":"+B(o.diagnostic_expected_total>CURSOR_DIAGNOSTIC_MAX_ROWS || o.returned>CURSOR_DIAGNOSTIC_MAX_ROWS)+
      ",\"character_limit_reached\":"+B(expected_written<ArraySize(o.diagnostic_expected) || received_written<ArraySize(o.diagnostic_returned))+
      ",\"diagnostic_build_start_us\":"+U(begin)+",\"expected_prefix\":"+expected+"]"+
      ",\"returned_raw_rows\":"+received+"]}";
   // Defensive hard guard: never cut a JSON string or silently emit an oversized record.
   if(StringLen(text)>CURSOR_DIAGNOSTIC_MAX_CHARS)
      text="{\"kind\":\"cursor_prefix_mismatch_diagnostic\",\"diagnostic_schema\":1,\"symbol\":"+J(SYMBOL)+
         ",\"parent_sequence\":"+U(o.sequence)+",\"diagnostic_only\":true,\"rows_are_new\":false,\"accepted_rows\":0"+
         ",\"reason\":\"CURSOR_PREFIX_CONTENT_MISMATCH\",\"cursor_before\":{\"time_msc\":"+L(o.cursor_before)+
         ",\"ordinal\":"+U(o.ordinal_before)+"},\"query_start_us\":"+U(o.query_start)+
         ",\"query_end_us\":"+U(o.query_end)+",\"query_duration_us\":"+U(o.query_end-o.query_start)+
         ",\"returned_rows\":"+IntegerToString(o.returned)+",\"expected_prefix_total\":"+IntegerToString(o.diagnostic_expected_total)+
         ",\"first_response_index\":"+IntegerToString(o.mismatch_row)+",\"first_prefix_index\":"+IntegerToString(o.mismatch_prefix)+
         ",\"different_fields\":"+DifferentTickFields(o.mismatch_expected,o.mismatch_received)+
         ",\"first_expected\":"+DiagnosticTick(o.mismatch_expected)+",\"first_received\":"+DiagnosticTick(o.mismatch_received)+
         ",\"max_record_chars\":"+IntegerToString(CURSOR_DIAGNOSTIC_MAX_CHARS)+
         ",\"max_rows_per_array\":"+IntegerToString(CURSOR_DIAGNOSTIC_MAX_ROWS)+
         ",\"truncated\":true,\"character_limit_reached\":true,\"allocation_failed\":"+B(o.diagnostic_allocation_failed)+
         ",\"expected_prefix_written\":0,\"returned_rows_written\":0,\"expected_prefix\":[],\"returned_raw_rows\":[]}";
   string primary_fault=fault;
   WriteLine(text);
   fault=primary_fault; // Diagnostic I/O cannot disguise the original continuity failure.
}

void FlushObservations(int limit)
{
   for(int n=0;n<limit && queued>0;n++)
   {
      CObservation *o=queue[head];queue[head]=NULL;head=(head+1)%QUEUE_CAPACITY;queued--;
      ulong write_start=GetMicrosecondCount();
      string text="{\"kind\":"+J(o.kind)+",\"symbol\":"+J(SYMBOL)+",\"sequence\":"+U(o.sequence)+
         ",\"trigger\":"+J(o.trigger)+",\"handler_start_us\":"+U(o.handler_start)+
         ",\"handler_end_us\":"+U(o.handler_end)+",\"handler_duration_us\":"+U(o.handler_end-o.handler_start)+
         ",\"has_query\":"+B(o.kind=="book_snapshot" || o.kind=="tick_batch")+
         ",\"query_start_us\":"+U(o.query_start)+",\"query_end_us\":"+U(o.query_end)+
         ",\"query_duration_us\":"+U(o.query_end-o.query_start)+
         ",\"time_local_before\":"+L((long)o.local_before)+",\"time_local_after\":"+L((long)o.local_after)+
         ",\"time_gmt_before\":"+L((long)o.gmt_before)+",\"time_gmt_after\":"+L((long)o.gmt_after)+
         ",\"time_current\":"+L((long)o.server_time)+",\"ok\":"+B(o.ok)+",\"error\":"+IntegerToString(o.error)+
         ",\"write_start_us\":"+U(write_start)+",\"queue_delay_us\":"+U(write_start-o.handler_end);
      if(o.kind=="book_snapshot")
      {
         text+=",\"dom_market_time_msc\":null,\"raw_levels\":[";
         for(int i=0;i<ArraySize(o.book);i++) { if(i) text+=",";text+=BookRow(o.book[i],i); }
         text+="],\"best_bid_levels\":"+BestSide(o.book,BOOK_TYPE_BUY)+
            ",\"best_ask_levels\":"+BestSide(o.book,BOOK_TYPE_SELL);
      }
      if(o.kind=="tick_batch")
      {
         text+=",\"returned_rows\":"+IntegerToString(o.returned)+",\"cursor_before\":{\"time_msc\":"+L(o.cursor_before)+
            ",\"ordinal\":"+U(o.ordinal_before)+"},\"cursor_after\":{\"time_msc\":"+L(o.cursor_after)+
            ",\"ordinal\":"+U(o.ordinal_after)+"},\"rows_are_new\":"+B(o.ok)+",\"ticks\":[";
         for(int i=0;i<ArraySize(o.ticks);i++) { if(i) text+=",";text+=TickRow(o.ticks[i],o.ordinals[i]); }
         text+="]";
      }
      text+=o.continuity_json;
      WriteLine(text+"}");WriteCursorDiagnostic(o);delete o;
   }
}

void CaptureBook(string trigger)
{
   CObservation *o=NewObservation("book_snapshot",trigger);if(o==NULL) return;
   o.local_before=TimeLocal();o.gmt_before=TimeGMT();o.query_start=GetMicrosecondCount();
   ResetLastError();o.ok=MarketBookGet(SYMBOL,o.book);o.error=GetLastError();
   o.query_end=GetMicrosecondCount();o.local_after=TimeLocal();o.gmt_after=TimeGMT();o.server_time=TimeCurrent();
   if(!o.ok) query_errors++;
   if(o.ok && ArraySize(o.book)==0) empty_books++;
   o.handler_end=GetMicrosecondCount();Enqueue(o);
}

// Exact ordered field comparison: repetitions are positions, never a set.
bool SameTick(const MqlTick &a,const MqlTick &b)
{
   return MathIsValidNumber(a.bid) && MathIsValidNumber(a.ask) &&
      MathIsValidNumber(a.last) && MathIsValidNumber(a.volume_real) &&
      MathIsValidNumber(b.bid) && MathIsValidNumber(b.ask) &&
      MathIsValidNumber(b.last) && MathIsValidNumber(b.volume_real) &&
      a.time==b.time && a.time_msc==b.time_msc && a.bid==b.bid &&
      a.ask==b.ask && a.last==b.last && a.volume==b.volume &&
      a.volume_real==b.volume_real && a.flags==b.flags;
}

string ContinuityFields(string state,string reason,ulong now,ulong seen=0,bool verified=false)
{
   return ",\"continuity_state\":"+J(state)+",\"continuity_reason\":"+J(reason)+
      ",\"recovery_episode_id\":"+U(waiting_prefix ? recovery_id : 0)+
      ",\"reconfirmation_attempt\":"+IntegerToString(waiting_prefix ? reconfirm_attempts : 0)+
      ",\"prefix_rows_seen\":"+U(seen)+",\"prefix_content_verified\":"+B(verified)+
      ",\"uncertainty_start_us\":"+U(waiting_prefix ? uncertainty_start : 0)+
      ",\"uncertainty_duration_us\":"+U(waiting_prefix ? now-uncertainty_start : 0);
}

void CloseRecovery(ulong now,string outcome,ulong final_sequence)
{
   if(!waiting_prefix) return;
   ulong duration=now-uncertainty_start;
   recovery_duration_total+=duration;
   if(duration>recovery_duration_max) recovery_duration_max=duration;
   if(outcome=="reconfirmed") recovery_reconfirmed++;else recovery_unverified++;
   last_recovery_json="{\"episode_id\":"+U(recovery_id)+",\"outcome\":"+J(outcome)+
      ",\"attempts\":"+IntegerToString(reconfirm_attempts)+
      ",\"uncertainty_start_us\":"+U(uncertainty_start)+",\"uncertainty_end_us\":"+U(now)+
      ",\"uncertainty_duration_us\":"+U(duration)+
      ",\"last_verified_us\":"+U(recovery_last_verified_us)+
      ",\"first_sequence\":"+U(recovery_first_sequence)+",\"final_sequence\":"+U(final_sequence)+
      ",\"anchor\":{\"time_msc\":"+L(cursor_ms)+",\"ordinal\":"+U(cursor_ordinal)+"}}";
   waiting_prefix=false;
}

void StopUnverified(ulong now)
{
   if(!waiting_prefix) return;
   if(fault=="") { fault="CURSOR_CONTINUITY_UNVERIFIED_EMPTY";query_errors++; }
   completed_window=false;
   string primary_fault=fault;
   CObservation *o=NewObservation("cursor_recovery","timer_or_deinit");
   fault=primary_fault;
   // Preserve an independent queue/I/O fault. Summary retains closure if allocation fails.
   CloseRecovery(now,fault, o==NULL ? global_sequence : o.sequence);
   if(o!=NULL)
   {
      o.ok=false;o.continuity_json=",\"recovery_result\":"+last_recovery_json;
      o.handler_end=GetMicrosecondCount();Enqueue(o);
   }
}

void RecoverTicks(string trigger)
{
   ulong now=GetMicrosecondCount();
   if(waiting_prefix && (now-uncertainty_start>=RECONFIRM_BUDGET_US ||
      reconfirm_attempts>=RECONFIRM_MAX_ATTEMPTS)) { StopUnverified(now);return; }
   CObservation *o=NewObservation("tick_batch",trigger);if(o==NULL) return;
   o.cursor_before=cursor_ms;o.ordinal_before=cursor_ordinal;
   o.cursor_after=cursor_ms;o.ordinal_after=cursor_ordinal;
   MqlTick rows[];
   o.local_before=TimeLocal();o.gmt_before=TimeGMT();o.query_start=GetMicrosecondCount();
   // Recheck immediately before the synchronous API call.
   if(waiting_prefix && o.query_start-uncertainty_start>=RECONFIRM_BUDGET_US)
   { delete o;StopUnverified(GetMicrosecondCount());return; }
   if(waiting_prefix) { reconfirm_attempts++;recovery_total_attempts++; }
   ResetLastError();int count=CopyTicks(SYMBOL,rows,COPY_TICKS_ALL,(ulong)cursor_ms,TICK_BATCH);
   o.error=GetLastError();o.returned=count;
   o.query_end=GetMicrosecondCount();o.local_after=TimeLocal();o.gmt_after=TimeGMT();o.server_time=TimeCurrent();
   last_tick_query=o.query_end;
   string reason="";
   if(count<0 || o.error!=0) reason="COPY_TICKS_FAILED_OR_PARTIAL";
   if(reason=="" && count==0 && cursor_ordinal>0)
   {
      recovery_empty_responses++;
      if(!waiting_prefix)
      {
         waiting_prefix=true;recovery_id++;reconfirm_attempts=0;
         uncertainty_start=o.query_start;recovery_first_sequence=o.sequence;
         recovery_last_verified_us=last_verified_us;
      }
      if(o.query_end-uncertainty_start>=RECONFIRM_BUDGET_US ||
         reconfirm_attempts>=RECONFIRM_MAX_ATTEMPTS) reason="CURSOR_CONTINUITY_UNVERIFIED_EMPTY";
      else
      {
         o.ok=false;pending_ticks=true;
         o.continuity_json=ContinuityFields("WAITING_PREFIX","EMPTY_REPLY",o.query_end);
         o.handler_end=GetMicrosecondCount();Enqueue(o);return;
      }
   }
   if(reason=="" && waiting_prefix && o.query_end-uncertainty_start>=RECONFIRM_BUDGET_US)
      reason="CURSOR_CONTINUITY_UNVERIFIED_EMPTY";
   ulong ordinal=0,seen_prefix=0,updated_ordinal=cursor_ordinal;
   long previous=-1,updated=cursor_ms;
   int accepted=0,last_group_start=0,mismatch_row=-1,mismatch_prefix=-1;
   bool content_verified=false;
   if(reason=="")
   {
      // Validation only. No candidates enter observations until continuity is proven.
      for(int i=0;i<count;i++)
      {
         long t=rows[i].time_msc;
         if(t<previous || (long)rows[i].time!=t/1000)
         { reason="TICK_ORDER_OR_TIME_ERROR";break; }
         if(t!=previous) last_group_start=i;
         ordinal=(t==previous) ? ordinal+1 : 1;previous=t;
         if(t==cursor_ms)
         {
            seen_prefix=ordinal;
            if(ordinal<=cursor_ordinal)
            {
               if(consumed_prefix_count!=(int)cursor_ordinal)
               { reason="CURSOR_PREFIX_EVIDENCE_UNAVAILABLE";break; }
               if(!SameTick(rows[i],consumed_prefix[(int)ordinal-1]))
               { reason="CURSOR_PREFIX_CONTENT_MISMATCH";mismatch_row=i;mismatch_prefix=(int)ordinal-1;break; }
            }
         }
         if(t<cursor_ms || (t==cursor_ms && ordinal<=cursor_ordinal)) continue;
         accepted++;updated=t;updated_ordinal=ordinal;
      }
      if(reason=="" && cursor_ordinal>0 && seen_prefix<cursor_ordinal)
         reason="CURSOR_PREFIX_DISAPPEARED";
      content_verified=reason=="" && cursor_ordinal>0;
      if(count==TICK_BATCH)
      {
         full_batches++;
         if(reason=="" && updated==cursor_ms && updated_ordinal==cursor_ordinal)
            reason="FULL_BATCH_WITHOUT_PROGRESS";
      }
   }
   if(reason=="" && (ArrayResize(o.ticks,accepted)!=accepted || ArrayResize(o.ordinals,accepted)!=accepted))
      reason="ALLOCATION_FAILED";
   if(reason=="")
   {
      int n=0;previous=-1;ordinal=0;
      for(int i=0;i<count;i++)
      {
         long t=rows[i].time_msc;
         ordinal=(t==previous) ? ordinal+1 : 1;previous=t;
         if(t<cursor_ms || (t==cursor_ms && ordinal<=cursor_ordinal)) continue;
         o.ticks[n]=rows[i];o.ordinals[n]=ordinal;n++;
      }
   }
   // The budget includes validation, allocation and staging; no serialization yet.
   now=GetMicrosecondCount();
   if(reason=="" && waiting_prefix && now-uncertainty_start>=RECONFIRM_BUDGET_US)
      reason="CURSOR_CONTINUITY_UNVERIFIED_EMPTY";
   o.ok=reason=="";
   o.continuity_json=ContinuityFields(o.ok ? "VERIFIED" : "STOPPED",reason,now,seen_prefix,content_verified && o.ok);
   if(!o.ok)
   {
      // Metadata only: rejected candidate payloads are never serialized.
      ArrayResize(o.ticks,0);ArrayResize(o.ordinals,0);
      fault=reason;query_errors++;
      if(waiting_prefix)
      { CloseRecovery(now,reason,o.sequence);o.continuity_json+=",\"recovery_result\":"+last_recovery_json; }
      if(mismatch_row>=0) CaptureCursorDiagnostic(o,rows,mismatch_row,mismatch_prefix);
   }
   else
   {
      if(waiting_prefix)
      { CloseRecovery(now,"reconfirmed",o.sequence);o.continuity_json+=",\"recovery_result\":"+last_recovery_json; }
      if(accepted>0)
      {
         // The last group contains the entire consumed prefix of the new cursor.
         consumed_prefix_count=count-last_group_start;
         for(int i=0;i<consumed_prefix_count;i++) consumed_prefix[i]=rows[last_group_start+i];
      }
      cursor_ms=updated;cursor_ordinal=updated_ordinal;
      o.cursor_after=cursor_ms;o.ordinal_after=cursor_ordinal;
      if(content_verified) last_verified_us=now;
      raw_ticks+=(ulong)accepted;
      for(int i=0;i<accepted;i++)
         if((o.ticks[i].flags & (TICK_FLAG_LAST|TICK_FLAG_VOLUME))!=0) trades++;else quotes++;
      pending_ticks=count==TICK_BATCH;
   }
   o.handler_end=GetMicrosecondCount();Enqueue(o);
}

int OnInit()
{
   if(_Symbol!=SYMBOL || MQLInfoInteger(MQL_TESTER) || DurationSeconds<1 ||
      TimerMilliseconds<10 || TimerMilliseconds>1000)
      return InitFailure("INVALID_PARAMETERS_OR_CONTEXT",INIT_PARAMETERS_INCORRECT);
   if(StringFind(AccountInfoString(ACCOUNT_COMPANY)+AccountInfoString(ACCOUNT_SERVER),"Clear")<0)
   { return InitFailure("TERMINAL_NOT_CLEAR",INIT_FAILED); }
   MqlTick initial={};
   ResetLastError();
   if(!SymbolInfoTick(SYMBOL,initial) || initial.time_msc<=0)
      return InitFailure("INVALID_INITIAL_TICK",INIT_FAILED,GetLastError());
   cursor_ms=initial.time_msc;cursor_ordinal=0;consumed_prefix_count=0;
   filename="WINZ26-event-joint-"+U(GetTickCount64())+"-"+L(ChartID())+".ndjson";
   if(FileIsExist(filename)) return InitFailure("OUTPUT_ALREADY_EXISTS",INIT_FAILED);
   ResetLastError();
   file=FileOpen(filename,FILE_WRITE|FILE_TXT|FILE_ANSI,0,CP_UTF8);
   file_open_error=GetLastError();
   if(file==INVALID_HANDLE) return InitFailure("FILE_OPEN_FAILED",INIT_FAILED,file_open_error);
   output_created=true;
   bool header_ok=WriteLine("{\"kind\":\"session\",\"schema\":1,\"symbol\":"+J(SYMBOL)+
      ",\"session_id\":"+J(filename)+",\"server\":"+J(AccountInfoString(ACCOUNT_SERVER))+
      ",\"terminal_data_path\":"+J(TerminalInfoString(TERMINAL_DATA_PATH))+
      ",\"terminal_build\":"+IntegerToString((int)TerminalInfoInteger(TERMINAL_BUILD))+
      ",\"start_gmt_s\":"+L((long)TimeGMT())+",\"start_local_s\":"+L((long)TimeLocal())+
      ",\"requested_seconds\":"+IntegerToString(DurationSeconds)+",\"cursor_seed_ms\":"+L(cursor_ms)+
      ",\"copy_ticks_batch_limit\":"+IntegerToString(TICK_BATCH)+",\"queue_capacity\":1024,\"reconfirm_max_attempts\":"+
      IntegerToString(RECONFIRM_MAX_ATTEMPTS)+",\"reconfirm_budget_us\":"+U(RECONFIRM_BUDGET_US)+"}");
   if(!header_ok) return InitFailure("FILE_WRITE_FAILED",INIT_FAILED,write_error);
   ResetLastError();subscribed=MarketBookAdd(SYMBOL);subscription_error=GetLastError();
   if(!subscribed) { query_errors++;return InitFailure("MARKET_BOOK_ADD_FAILED",INIT_FAILED,subscription_error); }
   ResetLastError();
   if(!EventSetMillisecondTimer(TimerMilliseconds))
   { timer_error=GetLastError();return InitFailure("TIMER_FAILED",INIT_FAILED,timer_error); }
   start_us=GetMicrosecondCount();last_flush=start_us;
   CaptureBook("OnInit");
   initialized=true;init_result=INIT_SUCCEEDED;
   Print("Experimental capture started; output: ",TerminalInfoString(TERMINAL_DATA_PATH),"\\MQL5\\Files\\",filename);
   return INIT_SUCCEEDED;
}

void OnBookEvent(const string &symbol)
{
   if(symbol!=SYMBOL || stopping || !subscribed || fault!="") return;
   if(GetMicrosecondCount()-start_us>=(ulong)DurationSeconds*1000000) return;
   book_events++;CaptureBook("OnBookEvent");
}

void OnTick()
{
   if(stopping || fault!="" || GetMicrosecondCount()-start_us>=(ulong)DurationSeconds*1000000) return;
   tick_events++;pending_ticks=true;
   CObservation *o=NewObservation("tick_trigger","OnTick");if(o==NULL) return;
   o.local_before=TimeLocal();o.local_after=o.local_before;o.gmt_before=TimeGMT();o.gmt_after=o.gmt_before;
   o.server_time=TimeCurrent();o.handler_end=GetMicrosecondCount();Enqueue(o);
}

void OnTimer()
{
   ulong begin=GetMicrosecondCount();timer_events++;
   if(waiting_prefix && (fault!="" || begin-uncertainty_start>=RECONFIRM_BUDGET_US ||
      begin-start_us>=(ulong)DurationSeconds*1000000)) StopUnverified(begin);
   if(fault!="" || begin-start_us>=(ulong)DurationSeconds*1000000)
   { completed_window=(fault=="");stopping=true;ExpertRemove();return; }
   // One bounded CopyTicks call per timer; recovery fallback even without OnTick.
   if(waiting_prefix || pending_ticks || begin-last_tick_query>=100000) RecoverTicks(waiting_prefix ? "prefix_reconfirmation" : pending_ticks ? "OnTick_or_backlog" : "timer_fallback");
   FlushObservations(WRITE_PER_TIMER);
   if(begin-last_flush>=1000000 && file!=INVALID_HANDLE && !io_failed) { FileFlush(file);last_flush=begin; }
   CObservation *o=NewObservation("timer_handler","OnTimer");if(o==NULL) return;
   o.handler_start=begin;o.handler_end=GetMicrosecondCount();Enqueue(o);
}

void OnDeinit(const int reason)
{
   if(waiting_prefix) StopUnverified(GetMicrosecondCount());
   if(!completed_window && fault=="")
      fault=initialized ? "EARLY_DEINITIALIZATION" : "INITIALIZATION_NOT_COMPLETED";
   stopping=true;EventKillTimer();bool release_ok=true;int release_error=0;
   if(subscribed) { ResetLastError();release_ok=MarketBookRelease(SYMBOL);release_error=GetLastError();subscribed=false; }
   FlushObservations(QUEUE_CAPACITY);
   if(file!=INVALID_HANDLE)
   {
      WriteLine("{\"kind\":\"summary\",\"symbol\":"+J(SYMBOL)+",\"deinit_reason\":"+IntegerToString(reason)+
         ",\"deinit_reason_name\":"+J(DeinitReasonName(reason))+
         ",\"initialized\":"+B(initialized)+",\"init_result\":"+IntegerToString(init_result)+
         ",\"init_error\":"+IntegerToString(init_error)+",\"file_open_error\":"+IntegerToString(file_open_error)+
         ",\"write_error\":"+IntegerToString(write_error)+
         ",\"fault\":"+J(fault)+",\"status\":"+J(fault=="" && release_ok ? "observed_not_certified_complete" : "incomplete")+
         ",\"release_ok\":"+B(release_ok)+",\"release_error\":"+IntegerToString(release_error)+
         ",\"subscription_error\":"+IntegerToString(subscription_error)+",\"timer_error\":"+IntegerToString(timer_error)+
         ",\"book_events\":"+U(book_events)+",\"tick_triggers\":"+U(tick_events)+",\"timer_events\":"+U(timer_events)+
         ",\"raw_ticks\":"+U(raw_ticks)+",\"trade_ticks\":"+U(trades)+",\"quote_ticks\":"+U(quotes)+
         ",\"dropped_observations\":"+U(dropped)+",\"peak_queue\":"+IntegerToString(peak_queue)+
         ",\"full_batches\":"+U(full_batches)+",\"empty_books\":"+U(empty_books)+",\"query_errors\":"+U(query_errors)+
         ",\"recovery_episodes\":"+U(recovery_id)+",\"recovery_reconfirmed\":"+U(recovery_reconfirmed)+
         ",\"recovery_unverified\":"+U(recovery_unverified)+",\"recovery_attempts\":"+U(recovery_total_attempts)+
         ",\"recovery_empty_responses\":"+U(recovery_empty_responses)+
         ",\"recovery_duration_total_us\":"+U(recovery_duration_total)+
         ",\"recovery_duration_max_us\":"+U(recovery_duration_max)+",\"last_recovery\":"+last_recovery_json+
         ",\"start_us\":"+U(start_us)+",\"end_us\":"+U(GetMicrosecondCount())+
         ",\"completed_window\":"+B(completed_window)+",\"no_final_tick_drain\":true}");
      FileFlush(file);FileClose(file);file=INVALID_HANDLE;
   }
   PrintFormat("Experimental capture stopped: fault=%s deinit_reason=%d (%s) initialized=%s init_result=%d init_error=%d file_open_error=%d write_error=%d output_created=%s path=%s",
      fault,reason,DeinitReasonName(reason),initialized ? "true" : "false",init_result,init_error,
      file_open_error,write_error,output_created ? "true" : "false",OutputPath());
}
