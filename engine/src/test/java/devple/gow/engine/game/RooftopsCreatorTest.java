package devple.gow.engine.game;

import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.content.Content;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Creator (원작 0x1ACCA8) — 43 문서 §19. 만드는 GO 의 행렬은 엔티티 레코드 행렬 · 소유 GO 관절 월드 (0x1A9CD0), 이름은 대소문자 무시 (레지스트리 0x175938 이
 * 대문자로 바꿔 해시), 그 밖의 이름은 레벨 WAD 오브젝트 원형 (ATHN05A 덩굴 통로의 병사 대 해골병 싸움). 예전 엔진은 소유 GO 인스턴스 자리 (묶음 GO 는 원점) 에
 * "chest"·"savepoint" 만 만들었다.
 */
class RooftopsCreatorTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static ScriptEntity entity(ScriptWorld w, String name) {
		return w.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	@Test
	void namesAreCaseInsensitiveLikeTheObjectRegistry() {
		assertTrue(SceneNav.creatorIs("chest", "chest"));
		assertTrue(SceneNav.creatorIs("Chest", "chest"), "Name0 \"Chest\" 40 개");
		assertTrue(SceneNav.creatorIs("goChest", "chest"), "0x1A4960: \"go\" 로 시작하면 그대로");
		assertTrue(SceneNav.creatorIs("SavePoint", "savepoint"));
		assertFalse(SceneNav.creatorIs("aoRED", "chest"));
		assertFalse(SceneNav.creatorIs(null, "chest"));
	}

	/** ATHN05A 덩굴 통로 위 발코니 (영상 6661–6671 초록 상자): 묶음 GO gochestgroup (인스턴스 원점) 의 상자 둘은 엔티티 행렬 자리에 따로. */
	@Test
	void groupedChestsSitAtTheirEntityMatrices() throws Exception {
		ScriptWorld w = new ScriptWorld(level("ATHN05A"), new ScriptHost(), new ScriptListener() {
		});
		ScriptEntity c12 = entity(w, "ChestGroup-chest12"), c13 = entity(w, "ChestGroup-chest13"), c3 = entity(w, "chest3");
		float[] owner = c12.owner.instance().pos();
		assertEquals(0, owner[0] * owner[0] + owner[1] * owner[1] + owner[2] * owner[2], 1e-3f, "묶음 GO 인스턴스는 원점");
		float[] m12 = SceneNav.creatorMatrix(c12), m13 = SceneNav.creatorMatrix(c13);
		assertArrayEquals(new float[]{614.5f, 624f, 2948.7f}, new float[]{m12[12], m12[13], m12[14]}, 0.2f);
		assertArrayEquals(new float[]{614.5f, 624f, 3065.5f}, new float[]{m13[12], m13[13], m13[14]}, 0.2f);
		for (int r = 0; r < 3; r++) {
			assertEquals(1f, (float) Math.sqrt(m12[r * 4] * m12[r * 4] + m12[r * 4 + 1] * m12[r * 4 + 1] + m12[r * 4 + 2] * m12[r * 4 + 2]), 1e-4f, "회전 행 정규화");
		}
		assertNotEquals(SceneNav.creatorKey(c12), SceneNav.creatorKey(c13), "묶음 상자마다 키");
		// 보통 상자: 엔티티 행렬 = 소유 GO 자리 → 키 = 소유 GO 이름 (Vis·이음 상태가 그 이름으로 찾는다)
		float[] m3 = SceneNav.creatorMatrix(c3), p3 = c3.owner.instance().pos();
		assertArrayEquals(p3, new float[]{m3[12], m3[13], m3[14]}, 0.05f);
		assertEquals("gochest3", SceneNav.creatorKey(c3));
	}

	/** ATHN05B RespawnChest1-Chest1 (Name0 "Chest") 도 상자 — 05B 상자 Creator 6 개 (예전 equals 로는 5). */
	@Test
	void capitalisedChestCreatorsAreChests() throws Exception {
		ScriptWorld w = new ScriptWorld(level("ATHN05B"), new ScriptHost(), new ScriptListener() {
		});
		int chests = 0, exact = 0;
		for (ScriptEntity e : w.entities()) {
			if (e.type() != 11) {
				continue;
			}
			Object n = w.slot(e, "Name0");
			chests += SceneNav.creatorIs(n, "chest") ? 1 : 0;
			exact += "chest".equals(n) ? 1 : 0;
		}
		assertEquals(6, chests);
		assertEquals(5, exact);
		assertEquals("Chest", w.slot(entity(w, "RespawnChest1-Chest1"), "Name0"));
	}

	/**
	 * 병사 대 해골병: Creator (Name0 "aoRED", Filter 605) 가 만든 오브젝트를 같은 Filter 의 Animator (모드 3, 지연 0.75) 가 act "aoRED1a05Hl05" 로 튼다.
	 * 원형 goaored 의 그 act 는 4.33 초 반복.
	 */
	@Test
	void ambientFightersAreCreatedAndAnimatedByFilter() throws Exception {
		LevelData d = level("ATHN05A");
		List<String> created = new ArrayList<>();
		List<String> animated = new ArrayList<>();
		ScriptWorld[] ref = new ScriptWorld[1];
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				Object n = ref[0].slot(source, "Name0");
				if (n instanceof String s && s.startsWith("ao")) {
					String key = source.name().toLowerCase(java.util.Locale.ROOT);
					float[] m = SceneNav.creatorMatrix(source);
					ref[0].addActor(key, ((Number) ref[0].slot(source, "Filter")).intValue(), new float[]{m[12], m[13], m[14]});
					created.add(key);
				}
			}

			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				for (String o : objects) {
					animated.add(o + "=" + anim);
				}
			}
		});
		ref[0] = w;
		w.start();
		for (int i = 0; i < 180; i++) {
			w.tick(1 / 60f, 600, -16, 3010);
		}
		assertTrue(created.contains("a05hl05busepivot4bv-aored1a05hl05"), created.toString());
		assertEquals(605, ((Number) w.slot(entity(w, "a05HL05bUSEPIVOT4BV-aoRED1a05Hl05"), "Filter")).intValue());
		Set<String> anims = new HashSet<>(animated);
		assertTrue(anims.contains("a05hl05busepivot4bv-aored1a05hl05=aoRED1a05Hl05"), animated.toString());
		assertTrue(anims.contains("a05hl05busepivot4bv-aosks1a05hl05=aoSKS1a05Hl05"), animated.toString());
		assertEquals(1, animated.stream().filter(s -> s.equals("a05hl05cusepivot4bv-aored1a05hl05=aoRED1a05Hl05")).count(),
				"Filter 가 묶음마다 달라 (605, 607 …) 한 Animator 는 제 짝만 튼다");
		EffectLibrary lib = new EffectLibrary();
		lib.add(d.wad);
		EffectLibrary.EffectDef red = lib.resolve("goaored");
		assertNotNull(red);
		Animation fight = red.act("aoRED1a05Hl05");
		assertNotNull(fight);
		assertEquals(4.333f, fight.duration, 0.01f);
		assertTrue(fight.looping());
	}
}
