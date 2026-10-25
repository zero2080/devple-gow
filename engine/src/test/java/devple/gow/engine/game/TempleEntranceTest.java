package devple.gow.engine.game;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.fx.FxEnvironment;
import devple.gow.engine.fx.MaterialFx;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판도라 신전 입구 홀 (PAND00B) · 해 문 (PAND00C EntrySide) — QA vs PS2 영상 3520–3650 (43 §33).
 * <ul>
 *   <li>Vis 슬롯 16 (엔진 이름 Attr17) = 부품 타입 0x17 (BhvrServer) · 0x4 (ScriptServer): PAND00C 생성 스크립트 fogTubeL3-FogOff 가 관 안개 SCR_Fog 를 끈다</li>
 *   <li>머티리얼 ANM 색 트랙 (레이어 0 rgba): 해 문 글자 MAT_M01sfxLightRayCore 기본 rgba 0 + 트랙 1 → 0</li>
 * </ul>
 */
class TempleEntranceTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static WadFile wad(String name) throws Exception {
		Path p = ROM.resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Elf exe() throws Exception {
		Path p = ROM.toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	/** li reg, imm 의 값. */
	private static int li(Elf e, int pc) {
		R5900.Insn i = at(e, pc);
		assertEquals("li", i.mnem(), String.format("%08x %s", pc, i.text()));
		return Integer.decode(i.ops().substring(i.ops().indexOf(',') + 1).trim());
	}

	/**
	 * 원작 Vis 이펙터 0x1ABAA8: 슬롯 16 (+0xF8) 의 값을 0x1B1398(대상, 값, 타입) 으로 타입 0x17 · 0x4 에 건다. 서버 타입은 Servers_Create 0x17D218
	 * (BhvrServer 0x17, ScriptServer 4). SCR_Fog 처리 0x2467A8 은 부품 +0x4 비트 4 (켬) 가 없으면 안개를 걸지 않는다.
	 */
	@Test
	void visSlot16TogglesBehaviourAndScriptComponents() throws Exception {
		Elf e = exe();
		assertTrue(at(e, 0x1abcf8).text().contains("0xf8("), at(e, 0x1abcf8).text());
		assertEquals(0x17, li(e, 0x1abd2c));
		assertEquals(0x1b1398, at(e, 0x1abd38).target());
		assertEquals(0x4, li(e, 0x1abd48));
		assertEquals(0x1b1398, at(e, 0x1abd4c).target());
		// Servers_Create: "BhvrServer" a2 = 0x17, "ScriptServer" a2 = 4
		assertEquals(0x17, li(e, 0x17d31c));
		assertEquals(0x4, li(e, 0x17d384));
		// SCR_Fog 처리: lhu v1, 0x4(s1); andi v1, v1, 0x4; beq v1, zero → 갱신 0x20D220 건너뜀
		assertTrue(at(e, 0x2467f8).text().contains("0x4(s1)"), at(e, 0x2467f8).text());
		assertEquals("andi", at(e, 0x246800).mnem());
		assertEquals(0x20d220, at(e, 0x246840).target());
	}

	/** PAND00C 생성: fogTubeL3-FogOff (슬롯 16 = 2) → 관 안개 (CXT_default) 꺼짐 — PAND00B 홀 눈에서 PAND00C 안개는 없다 (영상 3520–3600 의 따뜻한 홀색). */
	@Test
	void pand00cTubeFogIsOffAfterCreation() throws Exception {
		WadFile w = wad("PAND00C");
		LevelData lv = new LevelData(w);
		ScriptWorld world = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		world.start();
		world.update(1 / 60f);
		assertTrue(world.scriptsOff("gofogtubel3"), "fogTubeL3-FogOff (생성 센서)");
		assertFalse(world.scriptsOff("gofogmaingroup"), "주 안개는 fogMainGroup-VisOn1 (켬)");
		FxEnvironment env = new FxEnvironment(new WadAssets(ROM.resolve("PAND00C.WAD")), lv);
		Vector3f hall = new Vector3f(800, 80, 1232);
		assertTrue(env.selectFogAt(hall), "스크립트 상태 없이는 CXT_default 관 안개가 걸렸다 (홀이 푸르게)");
		env.setScriptsOff(world::scriptsOff);
		assertFalse(env.selectFogAt(hall), "관 안개 꺼짐 + 주 안개 칸 밖 → PAND00C 안개 없음");
		assertFalse(env.fogActive());
	}

	/** 해 문 글자: MAT 레이어 rgba 0 (가산 = 안 보임), ANM 레이어 0 rgb 트랙 키 280 부터 1 → 0 (9.33–10 초) — 틀면 빛나다 꺼진다. */
	@Test
	void sunDoorLettersGlowFromTheLayerColourTrack() throws Exception {
		WadFile w = wad("PAND00C");
		MaterialResource mat = null;
		for (WadTag t : w.resources()) {
			if (t.name().equals("MAT_M01sfxLightRayCore") && t.hasData()) {
				mat = MaterialResource.parse(t);
			}
		}
		assertNotNull(mat);
		assertArrayEquals(new float[]{0, 0, 0, 1}, mat.layers().getFirst().rgba(), 1e-6f, "기본: 가산 색 0");
		MaterialAnim.Anim a = MaterialAnim.load(w).get("MAT_M01sfxLightRayCore");
		assertFalse(a.loop(), "0x42 = 스크립트가 켜는 1회");
		float[][] rgba = {mat.layers().getFirst().rgba()};
		float[] base = mat.layers().getFirst().rgba().clone();
		assertSame(base, MaterialFx.colorTint(a, mat.color(), rgba, 0, -1f, base, false), "아직 틀지 않음 → 기본 (어두운 글자, 영상 3610–3626)");
		assertEquals(1f, MaterialFx.colorTint(a, mat.color(), rgba, 0, 0f, base, false)[0], 1e-4f, "틀자마자 밝게 (영상 3634–3638)");
		assertEquals(1f, MaterialFx.colorTint(a, mat.color(), rgba, 0, 9f, base, false)[1], 1e-4f);
		assertEquals(0.5f, MaterialFx.colorTint(a, mat.color(), rgba, 0, 9f + 2 / 3f, base, false)[2], 0.02f);
		assertEquals(0f, MaterialFx.colorTint(a, mat.color(), rgba, 0, 10f, base, false)[0], 1e-4f, "10 초에 꺼짐 (영상 3642)");
		assertEquals(1f, MaterialFx.colorTint(a, mat.color(), rgba, 0, 0f, base, false)[3], 1e-6f, "알파 트랙 없음 → 그대로");
		assertArrayEquals(new float[]{0f, -1f}, MaterialFx.times(Float.NaN), "NaN = 아직 틀지 않은 스크립트 재질");
		assertArrayEquals(new float[]{2f, 2f}, MaterialFx.times(2f));
	}
}
