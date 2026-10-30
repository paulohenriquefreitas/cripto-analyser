package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.M5VisualAuditStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class M5VisualAuditStoreTest {
    M5VisualAuditStore.Pivot pivot(long time,String type,double price) {
        return new M5VisualAuditStore.Pivot("WINZ26:5:"+time+":"+type,type,price,time,time+900000,"CONFIRMED",null,5);
    }
    @Test void archiveKeepsOriginalTypesAndCausalHistoryWithoutDuplicatesOrFutureEvents() {
        var a=pivot(300000,"TOP",100);var b=pivot(600000,"BOTTOM",130);var store=new M5VisualAuditStore();
        var event=new M5VisualAuditStore.Replacement(a,b,30,b.confirmedAtMsc(),"SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE");
        var submission=new M5VisualAuditStore.Submission("replay-id","WINZ26",30,b.confirmedAtMsc(),List.of(event));
        store.record(submission);store.record(submission);
        assertThat(store.read("replay-id","WINZ26",30,b.confirmedAtMsc()-1)).isEmpty();
        assertThat(store.read("replay-id","WINZ26",30,b.confirmedAtMsc())).containsExactly(event);
        assertThat(store.read("replay-id","WINZ26",15,Long.MAX_VALUE)).isEmpty();
        var invalid=new M5VisualAuditStore.Replacement(a,b,29,b.confirmedAtMsc(),event.reason());
        assertThatThrownBy(()->store.record(new M5VisualAuditStore.Submission("replay-id","WINZ26",30,b.confirmedAtMsc(),List.of(invalid))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.record(new M5VisualAuditStore.Submission("replay-id","WINZ26",30,b.confirmedAtMsc()-1,List.of(event))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rewindDoesNotExposeABreakLearnedAfterTheReplacement() {
        var original=pivot(300000,"TOP",100);
        var broken=new M5VisualAuditStore.Pivot(original.id(),original.type(),original.price(),original.extremeTimeMsc(),
            original.confirmedAtMsc(),"BROKEN",3000000L,5);
        var next=pivot(600000,"BOTTOM",120);var store=new M5VisualAuditStore();
        var event=new M5VisualAuditStore.Replacement(broken,next,20,next.confirmedAtMsc(),"SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE");
        store.record(new M5VisualAuditStore.Submission("replay","WINZ26",30,4000000,List.of(event)));
        var historical=store.read("replay","WINZ26",30,next.confirmedAtMsc()).getFirst();
        assertThat(historical.oldPivot().state()).isEqualTo("CONFIRMED");
        assertThat(historical.oldPivot().brokenAtMsc()).isNull();
        assertThat(store.read("replay","WINZ26",30,4000000).getFirst().oldPivot().brokenAtMsc()).isEqualTo(3000000L);
    }

    @Test void olderScopeHistoryIsNotDiscardedWhenNewScopesAreArchived() {
        var store=new M5VisualAuditStore();var a=pivot(300000,"TOP",100);var b=pivot(600000,"BOTTOM",120);
        var event=new M5VisualAuditStore.Replacement(a,b,20,b.confirmedAtMsc(),"SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE");
        for(int i=0;i<513;i++)store.record(new M5VisualAuditStore.Submission("replay-"+i,"WINZ26",30,b.confirmedAtMsc(),List.of(event)));
        assertThat(store.read("replay-0","WINZ26",30,b.confirmedAtMsc())).containsExactly(event);
    }

}
