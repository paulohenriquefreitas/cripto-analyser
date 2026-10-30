package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Selection only. Originals are never removed; prior segment versions and decisions are append-only. */
public final class MicroZigZag {
    public record Decision(String pivotId,long atMsc,String action,String reason,String previousTerminalId,
                           String replacedMovementId,String movementId) {}
    private final List<StructuralLevel> chain=new ArrayList<>();
    private final List<DowStructureEngine.Movement> history=new ArrayList<>();
    private final List<Decision> decisions=new ArrayList<>();
    public void accept(StructuralLevel p,boolean dual) {
        var last=chain.isEmpty()?null:chain.getLast();
        if(dual) { decide(p,"AMBIGUOUS","Both extremes in one bar; intrabar order unknown; keep prior chain",last,null,null);return; }
        if(last!=null && p.extremeTimeMsc()<=last.extremeTimeMsc()) {
            decide(p,"REJECTED_TIME","Origin does not follow selected terminal",last,null,null);return;
        }
        if(last==null) {chain.add(p);decide(p,"START","First selected extreme; no prior endpoint",null,null,null);return;}
        if(last.type()==p.type()) {
            boolean extendsLast=p.type()==StructuralLevel.Type.TOP?p.price()>last.price():p.price()<last.price();
            if(!extendsLast) {
                decide(p,p.price()==last.price()?"EQUAL_RETAINED_EARLIEST":"LESS_EXTREME",
                    "Same side: retain most extreme; equal prices retain earliest origin",last,null,null);return;
            }
            var old=chain.size()>1?movement(chain.get(chain.size()-2),last):null;
            chain.set(chain.size()-1,p);
            var next=chain.size()>1?movement(chain.get(chain.size()-2),p):null;
            if(next!=null) history.add(next);
            decide(p,"TERMINAL_REPLACED","More extreme confirmed same-side fractal; prior segment retained in history",last,old,next);return;
        }
        boolean valid=last.type()==StructuralLevel.Type.TOP?p.price()<last.price():p.price()>last.price();
        if(!valid) {decide(p,"REJECTED_PRICE","Opposite extreme has zero or inconsistent directional amplitude",last,null,null);return;}
        chain.add(p);var next=movement(last,p);history.add(next);
        decide(p,"SEGMENT_CREATED","Confirmed alternating chronological endpoints; no amplitude threshold",last,null,next);
    }
    private void decide(StructuralLevel p,String action,String reason,StructuralLevel last,
            DowStructureEngine.Movement old,DowStructureEngine.Movement next) {
        decisions.add(new Decision(p.id(),p.confirmedAtMsc(),action,reason,last==null?null:last.id(),old==null?null:old.id(),next==null?null:next.id()));
    }
    private DowStructureEngine.Movement movement(StructuralLevel a,StructuralLevel b) {
        return new DowStructureEngine.Movement(a.id()+"->"+b.id(),a.id(),b.id(),Math.max(a.confirmedAtMsc(),b.confirmedAtMsc()),b.type()==StructuralLevel.Type.TOP?"UP":"DOWN");
    }
    public List<String> selectedIds(){return chain.stream().map(StructuralLevel::id).toList();}
    public List<DowStructureEngine.Movement> movements(){var result=new ArrayList<DowStructureEngine.Movement>();for(int i=1;i<chain.size();i++)result.add(movement(chain.get(i-1),chain.get(i)));return List.copyOf(result);}
    public List<DowStructureEngine.Movement> history(){return List.copyOf(history);}
    public List<Decision> decisions(){return List.copyOf(decisions);}
    public String participation(String id) {
        if(chain.stream().anyMatch(p->p.id().equals(id)))return "STRUCTURE";
        if(decisions.stream().anyMatch(d->d.pivotId().equals(id)&&(d.action().equals("START")||d.action().equals("SEGMENT_CREATED")||d.action().equals("TERMINAL_REPLACED"))))return "SUPERSEDED";
        return "LOCAL";
    }
}
