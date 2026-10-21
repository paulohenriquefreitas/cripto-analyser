"""Manual Python companion to WinZ26NativeClockProbe.mq5; no time correction."""
import argparse
import json
from pathlib import Path

from mt5_clock_probe import duration, run
from mt5_dom_probe import json_safe


class TerminalEvidence:
    def __init__(self, api):
        self.api=api
        self.metadata={}

    def __getattr__(self,name): return getattr(self.api,name)

    def initialize(self,**kwargs):
        ok=self.api.initialize(**kwargs)
        if ok:
            info=self.api.terminal_info()
            self.metadata={'terminal':info,'version':self.api.version()}
        return ok


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds',type=duration,default=10)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    import MetaTrader5 as mt5
    with args.output.open('x',encoding='utf-8') as out:
        proxy=TerminalEvidence(mt5)
        report=run(proxy,args.seconds)
        report['native_comparison_metadata']=proxy.metadata
        report['mql5_only_unavailable_fields']=['TimeCurrent','TimeTradeServer','TimeGMT']
        out.write(json.dumps(json_safe(report),ensure_ascii=False,allow_nan=False,indent=2)+'\n')
    print(f"status={report['status']} output={args.output.resolve()}")
    return 0 if report['status']=='new_ticks_observed' else 1


if __name__=='__main__': raise SystemExit(main())
