"""Rebuild validation artifacts from frozen Java principal-Dow Replay snapshots. No trading logic."""
import csv, json
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter
ROOT=Path(__file__).resolve().parent/'dow-fifty'
def clock(t):return datetime.fromtimestamp(t/1000,timezone.utc).strftime('%H:%M')
timeline=[];events=[];checkpoints=[];summary=[]
for name in ['WINZ26','WIN_N']:
    views=json.loads((ROOT/(name+'-prefixes.json')).read_text(encoding='utf-8'))
    s=views[-1]['dowFifty'];first=next(i for i,v in enumerate(views) if v['dowFifty']['currentWaveId'])
    touch=next(e['atMsc'] for e in s['events'] if e['kind']=='FIRST_TOUCH')
    invalidated=next(e['atMsc'] for e in s['events'] if e['kind']=='BREAK_INVALIDATED_FALSE_BREAK')
    choices=[('confirmed',first),('false-break',next(i for i,v in enumerate(views) if v['availableAtMsc']==invalidated)),('final',len(views)-1)]
    if name=='WINZ26':choices=[('waiting',first-1),('confirmed',first),('touch',next(i for i,v in enumerate(views) if v['availableAtMsc']==touch)),('false-break',next(i for i,v in enumerate(views) if v['availableAtMsc']==invalidated)),('replaced',next(i for i,v in enumerate(views) if len(v['dowFifty']['references'])==2)),('final',len(views)-1)]
    for label,index in choices:
        file=name+'-'+label
        (ROOT/(file+'.json')).write_text(json.dumps(views[index],indent=2),encoding='utf-8')
        checkpoints.append(dict(name=file,symbol=views[index]['symbol'],cursor=clock(views[index]['availableAtMsc']),currentWaveId=views[index]['dowFifty']['currentWaveId']))
    for i,ref in enumerate(s['references']):
        w=ref['wave'];a=w['initial'];b=w['terminal']
        previous=s['references'][i-1]['wave']['id'] if i else None
        following=s['references'][i+1]['wave']['id'] if i+1<len(s['references']) else None
        timeline.append(dict(symbol=s['symbol'],waveId=w['id'],direction=w['direction'],
            initialId=a['id'],initialPrice=a['price'],initialFormation=clock(a['extremeTimeMsc']),initialConfirmation=clock(a['confirmedAtMsc']),
            terminalId=b['id'],terminalPrice=b['price'],terminalFormation=clock(b['extremeTimeMsc']),terminalConfirmation=clock(b['confirmedAtMsc']),
            high=w['high'],low=w['low'],level50=w['level50'],recognizedAt=clock(w['recognizedAtMsc']),
            endedAt=clock(ref['endedAtMsc']) if ref['endedAtMsc'] else None,state=ref['state'],sourceEventId=w['sourceEventId'],sealingPivotId=w['sealingPivotId'],
            supersedes=previous,replacedBy=following,decision='First uniquely sealed wave' if i==0 else 'New uniquely sealed principal wave supersedes previous reference'))
    events.extend(dict(symbol=s['symbol'],time=clock(e['atMsc']),**e) for e in s['events'])
    summary.append(dict(symbol=s['symbol'],prefixes=len(views),waves=len(s['references']),cutoff=clock(views[-1]['availableAtMsc']),events=dict(Counter(e['kind'] for e in s['events']))))
for file,rows in [('waves',timeline),('events',events)]:
    (ROOT/(file+'.json')).write_text(json.dumps(rows,indent=2),encoding='utf-8')
    with (ROOT/(file+'.csv')).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
(ROOT/'checkpoints.json').write_text(json.dumps(checkpoints,indent=2),encoding='utf-8')
(ROOT/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=True))
