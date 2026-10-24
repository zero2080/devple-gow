package devple.gow.engine.nav;

import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.RopeDef;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static devple.gow.engine.nav.HeroNavigatorTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 줄 잡기 이벤트 0x41B (1051): 원작 0x1C4EF8 → 0x1C4FD0 이 sub_001A8C78(0x41B, 0, 0, 0, 영웅) 를 보낸다 — ATHN03C 의 RopeAttachGroup-attachRope
 * (EventSensor 모드 1, 인자 0 == 0) 가 받아 메시지 591 "줄을 앞뒤로 흔들려면 ◎…" 를 띄운다 (PS2 영상 5612, 43 문서 §17).
 */
class RopeGrabEventTest {

	@Test
	void grabbingARopeNotifiesTheListenerOnce() {
		List<LevelCollision.Tri> t = new ArrayList<>();
		floor(t, -400, -400, 400, 400, 0, SurfaceAttr.GROUND);
		NavWorld w = new NavWorld(LevelCollision.of(t));
		NavRope rope = new NavRope("rope", RopeDef.Type.HANG, 0, 17.5f, v(0, 300, 0), v(0, 20, 0));
		w.ropes().add(rope);
		Body b = new Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		List<NavRope> grabbed = new ArrayList<>();
		nav.setListener(new HeroNavigator.Listener() {
			@Override
			public void ropeGrabbed(NavRope r) {
				grabbed.add(r);
			}
		});
		Pad in = new Pad();
		b.pos.set(4, 0, 0);
		in.down.add(PadButton.CROSS);
		tick(nav, b, w, in);
		assertEquals(NavState.ROPE, nav.state());
		for (int i = 0; i < 30; i++) {
			tick(nav, b, w, in);
		}
		assertEquals(List.of(rope), grabbed, "잡을 때 한 번");
		assertEquals(1051, HeroNavigator.EVENT_ROPE_GRAB);
	}

	@Test
	void athn03cRopeTutorialMessageShowsOnce() throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom")).resolve("ATHN03C.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		List<Integer> messages = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(new LevelData(WadFile.load(p)), new ScriptHost(), new ScriptListener() {
			@Override
			public void message(int msgId, float seconds) {
				messages.add(msgId);
			}
		});
		w.start();
		float[] s = w.startEntity().position();
		for (int i = 0; i < 10; i++) {
			w.tick(1 / 60f, s[0], s[1], s[2]);
		}
		assertFalse(messages.contains(591), "잡기 전에는 없음");
		for (int k = 0; k < 2; k++) {
			w.postEvent(HeroNavigator.EVENT_ROPE_GRAB, 0, 0, 0, w.player());
			for (int i = 0; i < 10; i++) {
				w.tick(1 / 60f, s[0], s[1], s[2]);
			}
		}
		assertEquals(List.of(591), messages.stream().filter(m -> m == 591).toList(), "두 번 잡아도 한 번: " + messages);
	}
}
