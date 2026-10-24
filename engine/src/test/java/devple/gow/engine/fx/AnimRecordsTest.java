package devple.gow.engine.fx;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANM act 상태 디스크립터의 원작 배치 (05 문서 §2.8.1, 43 §16): 세트 데이터 타입 항목 k 가 디스크립터 p2 개를 차지하고 (원작 채널 id = 앞 p2 합, 0x100EA8),
 * FXC 링크는 그 id 로 찾는다 (0x130240). ATHN03A 성문 불덩이 GateExplosion 이 이 배치에서만 맞다.
 */
class AnimRecordsTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Animation act(WadFile w, String set, String name) {
		for (AnimationSet.Group g : AnimationSet.load(w, set).groups) {
			for (Animation a : g.acts) {
				if (a.name.equals(name)) {
					return a;
				}
			}
		}
		throw new AssertionError(set + " " + name);
	}

	/** 디스크립터 수 = p2 합: GateExplosion 항목 11 개 [0(3), 10, 10, 10, 11, 10, 10, 10, 10, 11, 11] → 13, 히어로 [0(4), 5, 10×6] → 11. */
	@Test
	void recordCountIsTheSumOfP2() throws Exception {
		Animation ge = act(wad("ATHN03A"), "ANM_gateexplosion", "GateExplosion");
		assertEquals(11, ge.descrs.size(), "예전 배치 = 항목 수");
		assertEquals(13, ge.records.size());
		int[] types = {0, 0, 0, 10, 10, 10, 11, 10, 10, 10, 10, 11, 11};
		for (int r = 0; r < types.length; r++) {
			assertEquals(types[r], ge.records.get(r).typeId(), "디스크립터 " + r);
		}
		// 원소 크기 (+0x10) 가 형마다 하나: 타입 0 = 회전 s16 · 이동 f32 · 스케일 s16, 타입 10 = f32
		assertEquals(2, ge.records.get(0).unk10());
		assertEquals(4, ge.records.get(1).unk10());
		assertEquals(2, ge.records.get(2).unk10());
		Animation hero = act(wad("R_HERO0"), "ANM_hero", "air360");
		assertEquals(11, hero.records.size());
		assertEquals(1, hero.records.get(4).unk10(), "타입 5 = u8 관절 상태 (예전 \"타입 10 슬롯 2\")");
		assertSame(hero.posTracks, hero.recordTranslation, "타입 5 가 항목 1 인 세트는 예전 이동 = 디스크립터 1");
	}

	/**
	 * 디스크립터 표가 트랙 데이터보다 앞: 첫 트랙 데이터 오프셋 ≥ 0x64 + 0x14 · (p2 합). 항목 수로 끊으면 표 안쪽 기록 (GateExplosion 11·12 = 타입 11) 을 놓친다.
	 */
	@Test
	void trackDataStartsAfterTheWholeTable() throws Exception {
		Animation ge = act(wad("ATHN03A"), "ANM_gateexplosion", "GateExplosion");
		int min = Integer.MAX_VALUE;
		for (Animation.StateDescr d : ge.records) {
			if (d.trackCount() > 0) {
				min = Math.min(min, d.offset());
			}
		}
		assertTrue(min >= 0x64 + 0x14 * ge.records.size(), "0x" + Integer.toHexString(min));
		assertTrue(ge.records.get(11).trackCount() > 0 && ge.records.get(12).trackCount() > 0, "항목 수 너머의 디스크립터도 트랙이 있다");
	}

	/**
	 * 불덩이 (관절 2 Fireball03GE) 는 디스크립터 1 (타입 0 이동) 대로 0.43 초 동안 성문 쪽 (+z) 으로 날고, 관절 1·3 (메시) 은 움직이지 않는다.
	 * 예전 배치 ({@link Animation#sample}) 는 디스크립터 4 (생성률 150, "타입 11") 를 관절 2 x 로 읽어 불덩이가 0.45 초에 x 1000 옆으로 뛰었다.
	 */
	@Test
	void gateFireballFliesAlongTheTranslationRecord() throws Exception {
		WadFile w = wad("ATHN03A");
		EffectLibrary lib = new EffectLibrary();
		lib.add(w);
		EffectLibrary.EffectDef d = lib.resolve("gogateexplosion");
		assertNotNull(d);
		Animation a = d.anim();
		float[] inst = null;
		for (LevelLayout.Placement p : new LevelData(w).layout.placements()) {
			if (p.instance().name().equalsIgnoreCase("gogateexplosion")) {
				inst = p.instance().matrix();
			}
		}
		assertNotNull(inst);
		float[] j2a = world(d, a, 0f, 2, inst), j2b = world(d, a, 0.45f, 2, inst);
		assertArrayEquals(new float[]{-819, 1074, -1048}, j2a, 2f, "0 초");
		assertArrayEquals(new float[]{-830, 1152, -531}, j2b, 2f, "도착 (프레임 13)");
		for (float t = 0; t <= a.duration; t += 0.05f) {
			assertArrayEquals(world(d, a, 0, 1, inst), world(d, a, t, 1, inst), 0.5f, "관절 1 고정 t=" + t);
			assertArrayEquals(world(d, a, 0, 3, inst), world(d, a, t, 3, inst), 0.5f, "관절 3 고정 t=" + t);
			float z = world(d, a, t, 2, inst)[2];
			assertTrue(z >= -1049 && z <= -530, "불덩이 z 범위 " + z);
		}
		float oldX = Mat4.mul(a.sample(d.skeleton(), 0.45f, false).world(d.skeleton())[2], inst)[12];
		assertTrue(Math.abs(oldX - j2b[0]) > 500, "예전 배치의 옆 튐 (회귀 표시) " + oldX);
	}

	private static float[] world(EffectLibrary.EffectDef d, Animation a, float t, int j, float[] inst) {
		float[] m = Mat4.mul(d.pose(a, t).world(d.skeleton())[j], inst);
		return new float[]{m[12], m[13], m[14]};
	}

	/**
	 * 생성률: FBFemit2GE (링크 4) 는 원작 채널 id 4 = 타입 10 → 0 → 150/초 (프레임 1–13) → 0, FBSemit4GE (3) 50, FXC_GE (5) 100.
	 * 예전 배치는 디스크립터 4 를 "타입 11" 로 보아 채널이 비었다 (불꽃 본체가 안 나옴). 링크 22–25 (폭발 이미터) 는 채널 13 개 밖이라 곡선 없음.
	 */
	@Test
	void gateFireballRatesUseChannelIds() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("ATHN03A"));
		EffectLibrary.EffectDef d = lib.resolve("gogateexplosion");
		Animation a = d.anim();
		float flame = -1, smoke = -1, ge = -1;
		for (EffectLibrary.EmitterDef e : d.emitters()) {
			switch (e.fxc().name()) {
				case "FXC_FBFemit2GE" -> flame = d.rate(a, e, 0.2f, true);
				case "FXC_FBSemit4GE" -> smoke = d.rate(a, e, 0.2f, true);
				case "FXC_GE" -> ge = d.rate(a, e, 0.2f, true);
				default -> {
					assertTrue(e.channel() >= a.recordChannels.size(), e.fxc().name() + " 링크 " + e.channel());
					assertEquals(0f, d.rate(a, e, 0.2f, true), 1e-6f);
				}
			}
			assertEquals(0f, d.rate(a, e, 0.8f, true), 1e-6f, e.fxc().name() + " 끝나면 0");
		}
		assertEquals(150f, flame, 1e-3f);
		assertEquals(50f, smoke, 1e-3f);
		assertEquals(100f, ge, 1e-3f);
		assertTrue(a.channelGroups.get(4).isEmpty(), "예전 배치: 디스크립터 4 = \"타입 11\" → 채널 없음");
		assertTrue(d.hasRateChannel(a));
	}

	/** R_PERMK 상자 chestGrab: 타입 0 뒤가 모두 타입 10 인 세트는 두 배치의 채널이 같다 (3/6/9/12 = MSemit1/HSemit1/PSemit1/WSemit1, 40 §3). */
	@Test
	void chestChannelsAgreeInBothLayouts() throws Exception {
		Animation a = act(wad("R_PERMK"), "ANM_chest", "chestGrab");
		for (int id : new int[]{3, 6, 9, 12}) {
			assertFalse(a.recordChannels.get(id).isEmpty(), "채널 " + id);
			assertSame(a.channelGroups.get(id), a.recordChannels.get(id));
		}
	}
}
