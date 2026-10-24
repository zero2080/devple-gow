package devple.gow.engine.ui;

import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HUD 미터 자동 표시 (원작 Progress_UpdateHud 0x1D7148): 값이 바뀌거나 싸울 때 MeterBar 1, 변화 없이 Meter Bar Hold Time (2.0 초) 이 지나면
 * MeterBar 0. PS2 영상: 탐험 중 미터 없음 (5664 초) → 공격 (5665 초) 에 뜸 → 5670 초 사라짐.
 */
class HudMeterTest {

	private static final float FRAME = 1 / 60f;

	private static PlayerState player() throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return new PlayerState(GameRules.load(WadFile.load(dir.resolve("R_PERMK.WAD"))));
	}

	private static HudBinding binding() {
		HudBinding hb = new HudBinding();
		hb.autoMeter = true;
		return hb;
	}

	/** 프레임 n 개 동안 미터 갱신, 미터가 내려간 프레임 번호 (1부터, 없으면 −1). */
	private static int framesUntilHidden(HudBinding hb, PlayerState ps, UiVariables v, boolean busy, int n) {
		for (int i = 1; i <= n; i++) {
			hb.meter(ps, v, busy, FRAME);
			if (!hb.meterShown()) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void holdTimeIsTheTweak() throws Exception {
		assertEquals(2.0f, player().meterHoldTime(), 1e-6f, "TWK_Player Meter Bar Hold Time");
	}

	@Test
	void newGameShowsTheMeterThenHidesAfterTheHoldTime() throws Exception {
		PlayerState ps = player();
		HudBinding hb = binding();
		UiVariables v = new UiVariables();
		hb.update(ps, v);
		assertNotEquals("1", v.get("PS2_MeterBar_Event"), "자동 표시는 첫 갱신에 띄우지 않는다");
		hb.meter(ps, v, false, FRAME); // 마지막으로 본 값 = 0 (부팅) → HP 100 이 다르다
		assertTrue(hb.meterShown());
		assertEquals("1", v.get("PS2_MeterBar_Event"));
		int hidden = framesUntilHidden(hb, ps, v, false, 300);
		assertEquals(120, hidden, 1, "2.0 초 = 120 프레임 뒤 내림");
		assertEquals("0", v.get("PS2_MeterBar_Event"));
		v.event("PS2_MeterBar_Event", -1);
		for (int i = 0; i < 300; i++) {
			hb.meter(ps, v, false, FRAME);
		}
		assertEquals("-1", v.get("PS2_MeterBar_Event"), "변화가 없으면 탐험 중 미터는 내려간 채");
	}

	@Test
	void attackingKeepsTheMeterUpUntilTwoSecondsAfterTheMove() throws Exception {
		PlayerState ps = player();
		HudBinding hb = binding();
		UiVariables v = new UiVariables();
		hb.meter(ps, v, false, FRAME);
		framesUntilHidden(hb, ps, v, false, 300);
		assertFalse(hb.meterShown());
		assertEquals(-1, framesUntilHidden(hb, ps, v, true, 600), "전투 기술 중 (combatant +0x4C) 은 계속 표시");
		assertTrue(hb.meterShown());
		assertEquals(120, framesUntilHidden(hb, ps, v, false, 300), 1, "기술이 끝나고 2 초");
	}

	@Test
	void valueChangesAndPickupsShowTheMeter() throws Exception {
		PlayerState ps = player();
		HudBinding hb = binding();
		UiVariables v = new UiVariables();
		hb.meter(ps, v, false, FRAME);
		framesUntilHidden(hb, ps, v, false, 300);
		ps.hp -= 10; // 맞음
		hb.meter(ps, v, false, FRAME);
		assertTrue(hb.meterShown(), "HP 변화");
		framesUntilHidden(hb, ps, v, false, 300);
		ps.hp = ps.maxHp();
		hb.meter(ps, v, false, FRAME);
		framesUntilHidden(hb, ps, v, false, 300);
		ps.pickupOrb(0, 5); // 가득 찬 HP 에 녹색 오브: 값은 그대로 — 흡수 0x1D77B0 이 표시 요청
		assertEquals(ps.maxHp(), ps.hp);
		hb.meter(ps, v, false, FRAME);
		assertTrue(hb.meterShown(), "오브 흡수");
		framesUntilHidden(hb, ps, v, false, 300);
		ps.redOrbs += 10;
		hb.meter(ps, v, false, FRAME);
		assertTrue(hb.meterShown(), "붉은 오브");
		framesUntilHidden(hb, ps, v, false, 300);
		ps.mpRegen = 10; // ATHN02D 미노타우로스 방 (Internal 0x12)
		assertEquals(-1, framesUntilHidden(hb, ps, v, false, 600), "마법 재생 중 (0x3044A4 ≠ 0) 은 계속 표시");
	}

	@Test
	void hiddenWhenDeadAndHideIsIdempotent() throws Exception {
		PlayerState ps = player();
		HudBinding hb = binding();
		UiVariables v = new UiVariables();
		hb.meter(ps, v, true, FRAME);
		assertTrue(hb.meterShown());
		ps.hp = 0;
		hb.meter(ps, v, true, FRAME);
		assertFalse(hb.meterShown(), "HP < 1 → HUD 리셋 (0x1D71CC)");
		v.event("PS2_MeterBar_Event", -1);
		hb.hideMeter(v);
		assertEquals("-1", v.get("PS2_MeterBar_Event"), "내려간 미터는 다시 내리지 않는다 (0x1BA170)");
	}

	@Test
	void seenValuesCarryAcrossLevels() throws Exception {
		PlayerState ps = player();
		HudBinding.MeterSeen seen = new HudBinding.MeterSeen();
		HudBinding a = binding();
		a.seen = seen;
		UiVariables v = new UiVariables();
		a.meter(ps, v, false, FRAME);
		framesUntilHidden(a, ps, v, false, 300);
		HudBinding b = binding(); // 다음 레벨 (원작 정적 0x29DC34.. 은 그대로)
		b.seen = seen;
		b.meter(ps, v, false, FRAME);
		assertFalse(b.meterShown(), "값이 같으면 새 레벨에서 미터가 뜨지 않는다");
	}

	@Test
	void testScenesKeepTheMeterUp() throws Exception {
		PlayerState ps = player();
		HudBinding hb = new HudBinding();
		UiVariables v = new UiVariables();
		hb.update(ps, v);
		assertEquals("1", v.get("PS2_MeterBar_Event"), "자동 표시가 아니면 첫 갱신에 띄운다 (UI·영웅 시험 씬)");
	}
}
