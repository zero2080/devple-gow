package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA vs PS2 영상 — 사막 싸움 (43 §31.2-7): 세이렌 불덩이 소환 빛 (goSIRsum*, PlayFX 플래그 0x4 = 기술 진행에 맞춤 + 창 끝 정지) 과 달리기 먼지
 * (goRunDust 를 act 한 바퀴). GL 없이 데이터 · 실행 파일만.
 */
class SirenSummonRunDustTest {

	private static Path rom() {
		return Path.of(System.getProperty("gow.rom", "../gow-rom"));
	}

	private static WadFile wad(String name) throws Exception {
		Path p = rom().resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Elf exe() throws Exception {
		Path p = rom().toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static String at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc).text();
	}

	/** srl … , n (decoder 가 십진/16진 어느 쪽으로 찍든). */
	private static boolean shift(String text, int n) {
		return text.startsWith("srl") && (text.endsWith(", " + n) || text.endsWith(", 0x" + Integer.toHexString(n)));
	}

	private static int playFxFlags(Tweaks tw, int template) {
		int[] hashes = {0x8EAB20CB, 0x65118923, MagicFx.PLAYFX_SYNC, 0xC6DD46A4};
		int[] bits = {0x1, 0x2, 0x4, 0x8};
		String n = String.format("/TweakTemplates/PlayFX/%03d/", template);
		int f = 0;
		for (int k = 0; k < hashes.length; k++) {
			if ((tw.rawHash(n, hashes[k]).orElse(0) & 0xFF) != 0) {
				f |= bits[k];
			}
		}
		return f;
	}

	/**
	 * PlayFX 템플릿 키 → 비트 (0x19BBE0): 셋째 키 해시 0x71BFA948 = 0x4, 그 비트면 이펙트 GO 애니 트랙 속도 +0x3C = 0 (0x19B308–0x19B320), 창 끝 정지 0x19A4A0 은
	 * Particle Effect 일 때 0x2 · 0x4 를 보고 정지 비트를 켠다 (0x19A5AC – 0x19A5D4).
	 */
	@Test
	void playFxBit4FreezesTheEffectTrackAndStopsAtTheEventEnd() throws Exception {
		Elf e = exe();
		assertTrue(at(e, 0x19bcb0).contains("0x71bf"), at(e, 0x19bcb0));
		assertTrue(at(e, 0x19bcbc).contains("0xa948"), at(e, 0x19bcbc));
		assertTrue(at(e, 0x19bcc4).contains("0x4"), at(e, 0x19bcc4)); // 비트 0x4
		assertTrue(shift(at(e, 0x19b30c), 2), at(e, 0x19b30c));
		assertTrue(at(e, 0x19b320).contains("sw") && at(e, 0x19b320).contains("zero, 0x3c(s0)"), at(e, 0x19b320)); // 속도 0
		assertTrue(shift(at(e, 0x19a5ac), 1), at(e, 0x19a5ac)); // Looped (0x2)
		assertTrue(shift(at(e, 0x19a5bc), 2), at(e, 0x19a5bc)); // 0x4
		assertTrue(at(e, 0x19a5d0).contains("ori") && at(e, 0x19a5d0).contains("0x1"), at(e, 0x19a5d0)); // 정지 비트
	}

	/**
	 * 세이렌 불덩이 (SIRFIREBALLLEFT/RIGHT 1.333 초) 가 부르는 PlayFX 122–127 (goSIRsum*) 은 0x1|0x4|0x8, act 5 초 — 창 0.00–0.70 끝이면 act 3.5 초라
	 * 소환 분출 (FXC_SSAGemit2 4.0 초부터) 에 닿지 않는다. 같은 효과는 5 초짜리 SIRSUMMON (속도 1.5) 에서 진행 0.8 = TARGET_FX 0.82 에 터진다.
	 * 외눈박이의 PlayFX 효과 act 도 기술 애니와 길이가 같다 (cy2Swipe 3.333, cy2Overhead 7.333) — 진행에 맞추도록 만들어진 데이터.
	 */
	@Test
	void sirenSummonGlowIsSyncedToTheFireballAndStopsBeforeTheBurst() throws Exception {
		WadFile sid = wad("R_SID");
		Tweaks tw = new Tweaks();
		tw.load(sid);
		for (int t = 122; t <= 127; t++) {
			assertEquals(0xd, playFxFlags(tw, t), "PlayFX " + t);
		}
		EffectLibrary lib = new EffectLibrary();
		lib.add(sid);
		EffectLibrary.EffectDef ag2 = lib.resolve("goSIRsumAG2");
		assertEquals(5f, ag2.anim().duration, 1e-3f);
		EffectLibrary.EmitterDef em = ag2.emitters().getFirst();
		float windowEnd = 0.70f;
		for (float t = 0; t <= windowEnd * ag2.anim().duration; t += 0.05f) {
			assertEquals(0f, ag2.rate(em, t), 1e-3f, "창 안 act " + t + " 초에 분출 없음");
		}
		assertTrue(ag2.rate(em, 4.05f) > 300, "분출은 act 4.0 초부터");
		AnimationSet sidAnm = AnimationSet.load(sid, "ANM_sid");
		assertEquals(1.333f, act(sidAnm, "SIRfireBallLeft").duration, 1e-3f);
		assertEquals(5f, act(sidAnm, "SIRsummon").duration, 1e-3f);
		WadFile cy2 = wad("R_CY2");
		AnimationSet cyAnm = AnimationSet.load(cy2, "ANM_cy2");
		assertEquals(act(cyAnm, "cy2Swipe").duration, act(AnimationSet.load(cy2, "ANM_cy2swipe"), "CY2Swipe").duration, 1e-3f);
		assertEquals(act(cyAnm, "cy2Overhead").duration, act(AnimationSet.load(cy2, "ANM_cy2overhead"), "CY2Overhead").duration, 1e-3f);
	}

	private static Animation act(AnimationSet set, String name) {
		for (AnimationSet.Group g : set.groups) {
			for (Animation a : g.acts) {
				if (a.name.equals(name)) {
					return a;
				}
			}
		}
		return fail(name + " 없음");
	}

	/**
	 * 달리기 먼지: 사막 모래 MFX 040 → goRunDust (act runDust 0.2 초 반복, 정의 수명 무한). 원작은 GO 를 0x228558 → 0x228210 이 act 를 0x1007C8(애니, 0, …, 0x40, 1)
	 * 로 한 번 틀고 트랙이 끝나면 지운다 → 한 바퀴 0.2 초. 엔진은 0.6 초 (세 바퀴) 를 틀어 알파 5 % 먼지가 겹쳐 회청색 덩이가 됐다.
	 */
	@Test
	void runDustPlaysOneActLoop() throws Exception {
		Tweaks tw = new Tweaks();
		tw.load(wad("R_PERMK"));
		tw.load(wad("DEST01"));
		assertEquals("goRunDust", tw.s("/TweakTemplates/MFX/040/", "Run Dust Name", null));
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_PERMK"));
		EffectLibrary.EffectDef d = lib.resolve("goRunDust");
		assertFalse(d.oneShot());
		assertEquals(Float.POSITIVE_INFINITY, d.lifetime());
		assertEquals(0.2f, MagicFx.oneAct(d, 0.6f), 1e-3f);
		assertEquals(0.6f, MagicFx.oneAct(null, 0.6f));
		Elf e = exe();
		assertTrue(at(e, 0x221c7c).contains("0x228558"), at(e, 0x221c7c)); // 0x221A90 → 0x228558
		assertTrue(at(e, 0x228268).contains("0x40"), at(e, 0x228268));
		assertTrue(at(e, 0x22826c).contains("0x1"), at(e, 0x22826c)); // t0 = 1
		assertTrue(at(e, 0x228270).contains("0x1007c8"), at(e, 0x228270));
	}
}
