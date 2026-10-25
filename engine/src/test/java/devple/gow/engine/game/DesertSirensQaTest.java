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
import devple.gow.tools.model.GowMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.MeshObject;
import devple.gow.tools.model.VertexBatch;
import devple.gow.tools.model.VifParser;
import devple.gow.tools.model.VifUnpack;
import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

	/**
	 * V2-32 UV 는 V2-16 과 같은 4.12 정수다 (43 §31.2-5) [확실]: VU1 마이크로프로그램 (MSCAL 0/3/6, STCYCL CL 2/3/4 배치) 은 UNPACK 폭을 모른다 —
	 * V2-16 은 부호 확장, V2-32 는 그대로 같은 32비트 정수 칸에 들어가고 ITOF12 로 바뀐다. 예전 파서는 V2-32 를 float 비트로 읽어 4096 → 5.7e-42 (≈ 0).
	 */
	@Test
	void v2_32UvIsTheSame4_12IntegerAsV2_16() {
		VifUnpack pos = new VifUnpack(3, VifUnpack.VL_16, 2, false, true, 3, 1, 2, 4, new int[]{0, 0, 0, 0x8000, 16, 0, 0, 0x8000}, 0, null);
		int[] raw = {4096, -2048, 21799936, 0x7FFF}; // 1.0, −0.5, 5322.25 (ATHN03A MAT_M01stnBlock05), 7.99976
		VertexBatch b32 = new VertexBatch(new VifParser.Batch(List.of(
				new VifUnpack(1, VifUnpack.VL_32, 0, false, true, 3, 1, 2, 2, raw, 0, null), pos), 0, true, 0));
		assertEquals(1f, b32.uv[0][0]);
		assertEquals(-0.5f, b32.uv[0][1]);
		assertEquals(5322.25f, b32.uv[1][0]);
		VertexBatch b16 = new VertexBatch(new VifParser.Batch(List.of(
				new VifUnpack(1, VifUnpack.VL_16, 0, false, true, 3, 1, 2, 2, new int[]{4096, -2048, 0, 0x7FFF}, 0, null), pos), 0, true, 0));
		assertEquals(b16.uv[0][0], b32.uv[0][0]);
		assertEquals(b16.uv[0][1], b32.uv[0][1]);
		assertEquals(b16.uv[1][1], b32.uv[1][1]);
	}

	/**
	 * 사이렌 문 별 (영상 1500 · 2345 · 2453–2456 의 흰 별): DEST01 MDL_SirenGlow1–3 / SirenEnter1–3 (MAT_lambert13, 가산 텍스처) 의 사각형은 V2-32 UV
	 * 0..4096 = 0..1. float 비트로 읽으면 UV 가 전부 ≈ 0 이라 텍스처 모서리 화소 (투명) 만 찍혀 별이 보이지 않았다.
	 */
	@Test
	void sirenDoorStarsHaveFullTextureUvs() throws Exception {
		int models = 0;
		for (GowModel gm : GowModel.load(wad("DEST01"))) {
			if (!gm.name.matches("MDL_Siren(Glow|Enter)[123]")) {
				continue;
			}
			float max = 0;
			for (GowMesh m : gm.meshes) {
				for (GowMesh.Part p : m.parts) {
					for (GowMesh.Group g : p.groups()) {
						for (MeshObject o : g.objects()) {
							for (VertexBatch vb : o.batches) {
								assertNotNull(vb.uv, gm.name);
								for (float[] uv : vb.uv) {
									for (float f : uv) {
										assertTrue(f >= 0f && f <= 1f, gm.name + " UV " + f);
										max = Math.max(max, f);
									}
								}
							}
						}
					}
				}
			}
			assertEquals(1f, max, gm.name + " UV 최대");
			models++;
		}
		assertEquals(6, models);
	}

	/**
	 * SCR_GhostTrail (영상 1491–1497 · 2335 · 2445–2447 의 영혼 꼬리, 43 §31.2-6): DEST01 인스턴스 둘의 머리 = 관절 2 "tail", 꼬리 = 그 직속 자식 중
	 * 플래그 0x20 (joint1–8, 0 · 16 × 7 = 112, 기록 17). 원작 0x246918 → 0x20D988 (부품 +0x34 = 0x20E708), 기록 간격 8 (0x20E4DC) · 수 = ⌊길이 × 0.125⌋ + 3
	 * (0x20D9B8), 꼬리 플래그 0x20 (0x20DC00).
	 */
	@Test
	void sirenSoulGhostTrailIsTheTailJointChain() throws Exception {
		WadFile d1 = wad("DEST01");
		assertEquals(java.util.Map.of("gosirenspirit", 2, "gosirenspiritgrp", 2), devple.gow.engine.fx.GhostTrail.owners(d1));
		devple.gow.tools.anim.Skeleton sk = devple.gow.tools.anim.Skeleton.loadAll(d1).stream().filter(s -> s.name.equals("sirenspirit"))
				.findFirst().orElseThrow();
		assertEquals("tail", sk.joints.get(2).name());
		devple.gow.engine.fx.GhostTrail gt = devple.gow.engine.fx.GhostTrail.of(sk, 2,
				devple.gow.tools.level.LevelLayout.jointWorld(sk, devple.gow.tools.anim.Mat4.identity()));
		assertNotNull(gt);
		assertArrayEquals(new int[]{3, 4, 5, 6, 7, 8, 9, 10}, gt.chain());
		assertEquals(112f, gt.length(), 0.01f);
		assertEquals(17, gt.samples());
		Elf e = exe();
		call(e, 0x246940, 0x20d988); // SCR 처리 → 생성
		assertTrue(at(e, 0x20d9b8).text().contains("0x3e00"), at(e, 0x20d9b8).text()); // 0.125
		assertTrue(at(e, 0x20e4dc).text().contains("0x4100"), at(e, 0x20e4dc).text()); // 8.0
		assertTrue(at(e, 0x20dc00).text().contains("0x20"), at(e, 0x20dc00).text()); // 꼬리 관절 플래그
		assertTrue(at(e, 0x20da34).text().contains("0x34(s2)"), at(e, 0x20da34).text()); // 켤 때 = 0x20E708 → 기록 리셋
	}

	/**
	 * 꼬리는 머리가 지나간 길 위 호 길이 자리에 놓인다: +X 로 300 간 뒤 joint10 (112) 은 정확히 112 뒤, 이어 +Z 로 80 — joint3 (0) 은 머리, joint6 (48) 은
	 * Z 다리 위 48 뒤, joint10 은 모퉁이 지나 X 다리 위 약 32 뒤 (모퉁이 현만큼 더 뒤, Catmull-Rom 은 곧은 구간에서 정확). 회전은 머리 것. 기록은 생성
	 * 자세 (x = 0) 로 채운 채 시작한다 (0x20D988 → 0x20DE20).
	 */
	@Test
	void ghostTrailTailFollowsTheHeadPath() throws Exception {
		devple.gow.tools.anim.Skeleton sk = devple.gow.tools.anim.Skeleton.loadAll(wad("DEST01")).stream()
				.filter(s -> s.name.equals("sirenspirit")).findFirst().orElseThrow();
		float[] inst = devple.gow.tools.anim.Mat4.identity();
		devple.gow.engine.fx.GhostTrail gt = devple.gow.engine.fx.GhostTrail.of(sk, 2, devple.gow.tools.level.LevelLayout.jointWorld(sk, inst));
		float[][] out = null;
		for (int i = 1; i <= 150; i++) {
			inst[12] = 2f * i;
			out = gt.apply(devple.gow.tools.level.LevelLayout.jointWorld(sk, inst));
		}
		float[] h = devple.gow.tools.level.LevelLayout.jointWorld(sk, inst)[2];
		assertEquals(h[12] - 112, out[10][12], 0.05f);
		assertEquals(h[14], out[10][14], 0.05f);
		for (int i = 1; i <= 40; i++) {
			inst[14] = 2f * i;
			out = gt.apply(devple.gow.tools.level.LevelLayout.jointWorld(sk, inst));
		}
		float[][] w = devple.gow.tools.level.LevelLayout.jointWorld(sk, inst);
		h = w[2];
		assertEquals(h[12], out[3][12], 0.05f); // joint1: 머리 자리
		assertEquals(h[14], out[3][14], 0.05f);
		assertEquals(h[12], out[6][12], 0.05f); // joint4 (48): Z 다리
		assertEquals(h[14] - 48, out[6][14], 0.05f);
		// joint8 (112): 모퉁이 (80) 지나 X 다리 — 기록은 [0] 에서 머리 쪽 직선 8 씩이라 모퉁이를 현으로 자른다 (원작도 같은 식) → 직각이면 많아야
		// 8·(√2 − 1) ≈ 3.3 더 뒤 (기록 위상에 따라)
		assertEquals(h[12] - 32, out[10][12], 3.4f);
		assertTrue(out[10][12] <= h[12] - 32 + 0.05f, "현은 길을 줄인다 → 더 뒤");
		assertEquals(h[14] - 80, out[10][14], 0.05f);
		for (int k = 0; k < 3; k++) { // 회전 = 머리 회전 (정규화)
			float n = (float) Math.sqrt(h[k * 4] * h[k * 4] + h[k * 4 + 1] * h[k * 4 + 1] + h[k * 4 + 2] * h[k * 4 + 2]);
			for (int c = 0; c < 3; c++) {
				assertEquals(h[k * 4 + c] / n, out[10][k * 4 + c], 1e-3f);
			}
		}
	}

	/**
	 * 스크립트 Vis 슬롯 1 = 효과 부품 (타입 0x1E EffectsServer): 원작 Vis 0x1ABAA8 이 +0xB8 값을 0x1B1398(GO, 값, 0x1E) 로 건다 (0x1ABB6C) [확실]. 사막 영혼
	 * gosirenspirit 의 FXC_emitter31 (관절 1, 감산 PTC_particle31 = 검은 연기 60/초 · 0.7 초) 은 SoulOffInit/SoulOff 가 슬롯 0 과 1 을 함께 2 로, SoulOn 이 1 로 —
	 * 엔진은 슬롯 1 을 무시하고 이미터를 처음 자리에 고정해 날아가는 영혼에 연기가 없었다.
	 */
	@Test
	void visSlot1SwitchesTheSoulSmokeEmitter() throws Exception {
		Elf e = exe();
		assertTrue(at(e, 0x1abb20).text().contains("0xb8"), at(e, 0x1abb20).text()); // s3 = Vis +0xB8 (슬롯 1)
		assertEquals(0x1e, li(e, 0x1abb6c, "a2"));
		call(e, 0x1abb70, 0x1b1398);
		assertEquals(0x1e, li(e, 0x17d3fc, "a2")); // Servers_Create: "EffectsServer" = 0x1E
		WadFile d1 = wad("DEST01");
		for (String[] v : new String[][]{{"sirenSpirit-SoulOn", "1"}, {"sirenSpirit-SoulOff", "2"}, {"sirenSpirit-SoulOffInit", "2"},
				{"DoorSparkle-VisOff", "2"}}) {
			EscScript.Entity s = entity(d1, v[0]);
			assertTrue(handler(s, 0).startsWith("push_int " + v[1]), v[0] + " 슬롯 0");
			assertTrue(handler(s, 1).startsWith("push_int " + v[1]), v[0] + " 슬롯 1");
		}
		devple.gow.tools.fx.FxResources fx = new devple.gow.tools.fx.FxResources(d1);
		devple.gow.tools.level.LevelData lv = new devple.gow.tools.level.LevelData(d1);
		devple.gow.tools.level.LevelLayout.Placement pl = lv.layout.placements().stream()
				.filter(p -> p.instance().name().equals("gosirenspirit")).findFirst().orElseThrow();
		devple.gow.tools.fx.FxResources.Emitter em = fx.emitter("FXC_emitter31", pl.object().tag());
		assertEquals(1, em.header()[0]); // 관절 1 "siren"
		assertEquals(60f, em.rate(), 1e-3f);
		devple.gow.tools.fx.FxResources.Shape sh = fx.shape(em.shape(), pl.object().tag());
		assertEquals(0x42, sh.gsBlend()); // 감산 = 검은 연기
		assertEquals(0.7f, sh.lifespan(), 1e-3f);
	}
}
