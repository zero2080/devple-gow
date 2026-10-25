package devple.gow.engine.game;

import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.fx.FxEnvironment;
import devple.gow.engine.render.DrawStage;
import devple.gow.engine.render.ShadowRenderer;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA vs PS2 영상 — 길 잃은 영혼들의 사막 (DEST00 → DEST01 → DEST02, 영상 1040–2520 초, 43 §31). GL 없이 데이터 · 실행 파일 · 식만 검사.
 */
class DesertSirensQaTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static WadFile wad(String name) throws Exception {
		Path p = Content.file(rom(), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static WadFile permk() throws Exception {
		Path p = Content.file(rom(), Region.of(rom()).perm());
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Elf exe() throws Exception {
		Path p = rom().toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	private static int li(Elf e, int pc, String reg) {
		R5900.Insn i = at(e, pc);
		assertEquals("li", i.mnem(), String.format("%08x %s", pc, i.text()));
		assertTrue(i.ops().startsWith(reg + ","), String.format("%08x %s", pc, i.text()));
		return Integer.decode(i.ops().substring(i.ops().indexOf(',') + 1).trim());
	}

	private static void call(Elf e, int pc, int target) {
		assertEquals(target, at(e, pc).target(), String.format("%08x %s", pc, at(e, pc).text()));
	}

	private static MaterialResource.Layer firstLayer(WadFile w, String mat) {
		for (WadTag t : w.tags()) {
			if (t.hasData() && t.size() >= 4 && t.magic() == MaterialResource.MAGIC && t.name().equals(mat)) {
				return MaterialResource.parse(t).layers().getFirst();
			}
		}
		return fail(mat + " 없음");
	}

	/** 레벨 ESC 엔티티 하나 (이름). */
	static EscScript.Entity entity(WadFile w, String name) {
		for (WadTag t : w.tags()) {
			if (!t.hasData()) {
				continue;
			}
			try {
				for (EscScript.Entity e : new EscScript(t.data()).entities) {
					if (e.name().equals(name)) {
						return e;
					}
				}
			} catch (RuntimeException ignored) {
				// ESC 아님
			}
		}
		return fail(name + " 없음");
	}

	/** 핸들러 번호 → 바이트코드 문자열 (";" 구분). */
	static String handler(EscScript.Entity e, int id) {
		return e.handlers().stream().filter(h -> h.id() == id).findFirst()
				.map(h -> h.code().stream().map(EscOp::text).collect(Collectors.joining("; "))).orElse("");
	}

	/**
	 * 모래 폭풍 안개 (영상 1145–1153 · 1170–1300: 크레토스·석상이 안개에 묻힘): DEST01 SCR_Fog gofogregsand = (0.52, 0.42, 0.30), Fog_A 1, CentreZ 16500,
	 * 블렌드 0 — 0x1275E0 경로라 계수 = (128 − ⌊z'/256⌋)/128 (16 §1.2). 메시지 345 자리 (눈 (2394, 574, 1279)) 에서 영웅 175.5 → 0.55, 석상 297 → 0.74.
	 * 엔진은 이 계수를 캐릭터 셰이더에 걸지 못해 (applyFog 가 프로그램을 묶지 않음) 크레토스·석상·사이렌이 안개를 전혀 받지 않았다.
	 */
	@Test
	void desertFogHazesCharactersAtCameraDistance() throws Exception {
		EnvParams p = new EnvParams(wad("DEST01"));
		EnvParams.Fog f = p.fogs.stream().filter(x -> x.owner().equalsIgnoreCase("gofogregsand")).findFirst().orElseThrow();
		assertArrayEquals(new float[]{0.52f, 0.42f, 0.30f, 1f}, f.color(), 1e-3f);
		assertEquals(16500, f.centreZ());
		assertEquals(0, f.blend());
		assertEquals(0.555f, FxEnvironment.depthAlpha(f.centreZ(), 175.5f, FxEnvironment.viewFar), 0.01f);
		assertEquals(0.742f, FxEnvironment.depthAlpha(f.centreZ(), 297f, FxEnvironment.viewFar), 0.01f);
		assertEquals(0f, FxEnvironment.depthAlpha(f.centreZ(), 70f, FxEnvironment.viewFar)); // 가까운 화소 (z' ≥ 0x7FFF) 는 안개 없음
	}

	/** 그림자는 안개 노드 앞 (층 2 불투명 뒤, 0x160518) 이라 (1 − F)·38 만 남는다 — 안개를 이미 받은 화소에서 그만큼만 뺀다. */
	@Test
	void shadowSubtractionIsUnderTheFog() {
		assertEquals(ShadowRenderer.SUBTRACT, ShadowRenderer.foggedSubtract(0f, 0), 1e-6f);
		assertEquals(ShadowRenderer.SUBTRACT * 0.445f, ShadowRenderer.foggedSubtract(0.555f, 0), 1e-6f);
		assertEquals(0f, ShadowRenderer.foggedSubtract(1.2f, 0), 1e-6f);
		assertEquals(ShadowRenderer.SUBTRACT, ShadowRenderer.foggedSubtract(0.555f, 1), 1e-6f); // 가산 안개: c − 38 + F·f
		assertEquals(ShadowRenderer.SUBTRACT, ShadowRenderer.foggedSubtract(0.555f, 2), 1e-6f); // 감산 안개
	}

	/**
	 * 블레이드·아르테미스의 검은 층 2 (영상 1065 · 1150: 등의 블레이드도 크레토스와 같이 안개에 묻힘): 머티리얼 flags2 = 0x100 (반투명 단계 bit 40, 층 3·4 비트
	 * 0x18 없음). 층 3 을 거는 0x150738(…, 2) 는 Blades_Init 과 무기 궤적 0x12DE70 이 0x23EB58 로 만든 띠 인스턴스 (서버 vtable +0x2C 의 반환,
	 * +0xD4 = 3) 에 대한 것이다.
	 */
	@Test
	void bladeModelsAreLayer2AndLayer3IsTheChainStrip() throws Exception {
		for (String[] wm : new String[][]{{"R_WPN0_1", "MAT_M01Blade128"}, {"R_WPN0_1", "MAT_M02Blade128"}, {"R_BRSRK0", "MAT_Berserker1A"}}) {
			MaterialResource.Layer l = firstLayer(wad(wm[0]), wm[1]);
			assertEquals(0x100, l.flags2(), wm[1]);
			assertTrue(DrawStage.lateMaterial(l.renderFlags(), l.flags2()), wm[1] + " 은 그림자 합성 뒤 단계");
			assertEquals(0, l.flags2() & 0x18, wm[1] + " 은 층 3·4 가 아님");
		}
		Elf e = exe();
		// Blades_Init: 0x23EB58(사슬 띠 서술자) → 서버 vtable 호출 → 인스턴스 v0: +0xD4 = 3, 0x150738(v0, 2)
		call(e, 0x1f9f28, 0x23eb58);
		assertEquals(3, li(e, 0x1f9f90, "a1"));
		assertTrue(at(e, 0x1f9f9c).text().contains("0xd4(v0)"), at(e, 0x1f9f9c).text());
		assertEquals(2, li(e, 0x1f9fb0, "a1"));
		call(e, 0x1fa010, 0x150738);
		// 무기 궤적 0x12DE70: 같은 꼴 (0x23EB58 → +0xD4 = 3 → 0x150738(…, 2))
		call(e, 0x12e118, 0x23eb58);
		assertEquals(3, li(e, 0x12e178, "a1"));
		assertTrue(at(e, 0x12e184).text().contains("0xd4(v0)"), at(e, 0x12e184).text());
		assertEquals(2, li(e, 0x12e198, "a1"));
		call(e, 0x12e1f8, 0x150738);
	}

	/**
	 * 사막 입구 아테나 석상 (영상 1065 · 1145–1153 의 안개 속 석상): DEST01 d01Mi01a/b-aoXSAAI1/2 = SpawnAtStart, 템플릿 307 (레벨 WAD 자체 AI1 →
	 * 오브젝트 goaoxsa). DEST00 의 wadLoadDest01 LoadWad("Dest01") 로 DEST01 이 다른 슬롯일 때 시작 엔티티가 돌므로 다른 슬롯 레벨의 레벨 템플릿
	 * 종류로 내야 한다 (엔진은 RSRCS 종류만 찾아 "이웃 스폰 생략").
	 */
	@Test
	void desertAthenaStatueIsANeighbourLevelTemplate() throws Exception {
		WadFile d1 = wad("DEST01"), pk = permk();
		for (String n : new String[]{"d01Mi01a-aoXSAAI1", "d01Mi01b-aoXSAAI2"}) {
			EscScript.Entity s = entity(d1, n);
			assertEquals("SpawnEnemy", s.typeName());
			assertTrue(handler(s, 2).startsWith("push_true"), n + " SpawnAtStart"); // 슬롯 2 SpawnAtStart
			assertTrue(handler(s, 4).startsWith("push_int 307"), n + " TemplateId");
		}
		assertTrue(handler(entity(wad("DEST00"), "wadLoadDest01-EntrySensor1"), 4).contains("Dest01"));
		Tweaks tw = new Tweaks();
		tw.load(d1);
		assertEquals("aoxsa", EnemyFactory.levelTemplateObject(tw, 307));
		for (String r : EnemyFactory.resourceWads(d1)) {
			try {
				assertFalse(EnemyFactory.load(Content.file(rom(), r + ".WAD"), pk, -1).hasTemplate(307), r);
			} catch (RuntimeException soundOnly) {
				// 소리 전용 RSRCS
			}
		}
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom(), "DEST01.WAD"), pk, 307, "aoxsa");
		assertEquals(307, k.ai1());
		assertEquals("MDL_aoXSA", k.model());
	}
}
