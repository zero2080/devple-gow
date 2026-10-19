package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 원작 이펙트 GO 해석·수명 (40 문서): 이름 → GO 인스턴스 → 오브젝트, ANM 채널 생성률, 1회성 수명, 튜닝 템플릿 연결. */
class EffectTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	@Test
	void medusaEffectsResolve() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_MEDHD0"));
		EffectLibrary.EffectDef beam = lib.resolve("goEyeBeam");
		assertNotNull(beam);
		assertEquals("eyebeam", beam.object());
		assertEquals("MDL_eyeBeam", beam.model());
		assertFalse(beam.oneShot(), "광선 act 는 반복 (flags 0x1041) → 소유자가 끌 때까지");
		assertEquals(Float.POSITIVE_INFINITY, beam.lifetime());
		EffectLibrary.EffectDef flash = lib.resolve("GOMEDHEADFLASH");
		assertTrue(flash.oneShot());
		assertEquals(0.333f, flash.lifetime(), 1e-3f);
		assertEquals(1, flash.emitters().size());
		EffectLibrary.EmitterDef em = flash.emitters().getFirst();
		assertEquals("FXC_MHESemit", em.fxc().name());
		assertEquals(0, em.channel());
		// ANM 타입 10 채널 스트림 8: 프레임 0–9 5000/초, 9→10 에서 0 으로, act 이후 0
		assertEquals(5000f, flash.rate(em, 0.1f), 1e-2f);
		assertEquals(0f, flash.rate(em, 0.4f), 1e-6f);
		EffectLibrary.EffectDef nuke = lib.resolve("medheadnuke");
		assertNotNull(nuke, "go 접두 생략");
		assertEquals("MDL_medheadNuke", nuke.model());
	}

	@Test
	void bloodChannelsAndGravity() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_PERMK"));
		EffectLibrary.EffectDef b = lib.resolve("goSklBlood");
		assertEquals("sklblood", b.object());
		assertTrue(b.oneShot());
		assertEquals(0.4f, b.lifetime(), 1e-4f);
		assertEquals(3, b.emitters().size());
		EffectLibrary.EmitterDef e0 = b.emitters().getFirst();
		assertEquals(0, e0.fxc().rate(), "FXC 상수 생성률 0 — 채널이 생성률을 준다");
		assertEquals(0f, b.rate(e0, 0f), 1e-3f);
		assertEquals(280.63f, b.rate(e0, 1 / 30f), 0.05f);
		// 스트림 3 = 퍼짐 (프레임 0 0.1117 = FXC P+0x0C), 4 = 속력 (2.869 = P+0x10)
		assertEquals(e0.fxc().spread(), b.param(e0, 3, 0f, -1), 1e-4f);
		assertEquals(e0.fxc().speed(), b.param(e0, 4, 0f, -1), 1e-3f);
		float[] g = EffectLibrary.fieldAccel(b.fields());
		assertTrue(g[1] < -100 && Math.abs(g[0]) < 1e-3f && Math.abs(g[2]) < 1e-3f, "SKLgrav −7·(0,0,1) → 아래 112/s²");
		EffectLibrary.EffectDef dust = lib.resolve("goRunDust");
		assertFalse(dust.oneShot(), "runDust act 0.2초 반복");
		assertEquals(240f, dust.rate(dust.emitters().getFirst(), 1 / 30f), 1e-2f);
	}

	@Test
	void magicWadsResolve() throws Exception {
		EffectLibrary lib = new EffectLibrary();
		lib.add(wad("R_THBLT0"));
		lib.add(wad("R_RDEAD0"));
		lib.add(wad("R_LGHTN0"));
		assertEquals(0.8f, lib.resolve("goBoltHit").lifetime(), 1e-3f);
		assertEquals("MDL_Bolt", lib.resolve("goBolt").model());
		assertTrue(lib.resolve("goStdBolt").emitters().getFirst().fxc().rate() > 100, "꼬리 = 상수 생성률 이미터");
		assertEquals(Float.POSITIVE_INFINITY, lib.resolve("goStdBolt").lifetime(), "애니 없음 → 소유자가 끈다");
		assertEquals(0.25f, lib.resolve("goBoltChargeUp").scale(), 1e-4f, "인스턴스 스케일");
		assertEquals(1f, lib.resolve("goDeadHit").lifetime(), 1e-4f);
		assertEquals(1f, lib.resolve("goDeadDie").lifetime(), 1e-4f);
		assertNotNull(lib.resolve("goDedRed").model());
		assertNotNull(lib.resolve("goLightningAttackMain"));
		assertNotNull(lib.resolve("goLightningSingleBolt"));
		assertNull(lib.resolve("goNothingHere"));
	}

	@Test
	void templatesNameEffects() throws Exception {
		Tweaks tw = new Tweaks();
		tw.load(wad("R_PERMK"));
		assertEquals("goSklBlood", tw.s("/TweakTemplates/PlayFX/035/", "Effect Name", null));
		assertEquals("head", tw.s("/TweakTemplates/PlayFX/035/", "Joint Name", null));
		assertEquals("goCombo3fExplode", tw.s("/TweakTemplates/PlayFX/101/", "Effect Name", null));
		assertEquals(1f, tw.f("/TweakTemplates/PlayFX/101/", "Scale X", 0), 1e-6f);
		assertEquals("goMedHeadNuke", tw.s("/TweakTemplates/Concussion/000/", "Effect Name", null));
		assertEquals(1, tw.raw("/TweakTemplates/Concussion/000/", "Scale X").orElse(0) & 0xFF, "Medusa Nuke: Scale X 플래그");
		assertEquals(0, tw.raw("/TweakTemplates/Concussion/009/", "Scale X").orElse(0) & 0xFF, "7A Plume C (goRunDust): 배율 없음");
		assertEquals("goSklBlood", tw.sHash("/TweakTemplates/MFX/500/", MagicFx.MFX_BLOOD, null));
		assertEquals("goFleshSlice", tw.s("/TweakTemplates/MFX/500/", "Slice Hit Name", null));
		assertEquals("goRunDust", tw.s("/TweakTemplates/MFX/020/", "Run Dust Name", null));
		assertEquals("goBoltHit", tw.s("/TweakTemplates/Arrow/002/", "Hit Effect", null));
		assertEquals("goStdBolt", tw.s("/TweakTemplates/Arrow/002/", "Trail Name", null));
		assertEquals("goEyeBeam", tw.s("/Player/MedusaHead/", "Stage 0 Effect", null));
	}

	@Test
	void headlessLifetimes() throws Exception {
		EffectSystem fx = EffectSystem.headless();
		fx.add(wad("R_MEDHD0"));
		fx.add(wad("R_PERMK"));
		Vector3f eye = new Vector3f();
		EffectSystem.Effect flash = fx.play("goMedHeadFlash", EffectSystem.Anchor.at(new Vector3f(0, 50, 0), 0));
		EffectSystem.Effect beam = fx.play("goEyeBeam", EffectSystem.Anchor.at(new Vector3f(), 0));
		EffectSystem.Effect dust = fx.play("goRunDust", EffectSystem.Anchor.at(new Vector3f(), 0), 0.6f);
		assertNull(fx.play("goUnknown", EffectSystem.Anchor.at(new Vector3f(), 0)));
		assertEquals(5000f, flash.rates()[0], 1e-2f);
		for (int i = 0; i < 12; i++) {
			fx.update(1 / 60f, eye);
		}
		assertFalse(flash.done());
		assertEquals(0.2f, flash.time(), 1e-4f);
		assertEquals(50f, flash.world().getTranslation(new Vector3f()).y, 1e-4f);
		for (int i = 0; i < 12; i++) {
			fx.update(1 / 60f, eye);
		}
		assertTrue(flash.done(), "1회성: act 0.333초 뒤 종료 (0x2282D0)");
		assertFalse(fx.live().contains(flash));
		for (int i = 0; i < 30; i++) {
			fx.update(1 / 60f, eye);
		}
		assertTrue(dust.done(), "강제 수명 0.6초");
		assertFalse(beam.done(), "반복 광선은 계속");
		beam.stop();
		fx.update(1 / 60f, eye);
		assertTrue(fx.live().isEmpty());
		// 소유자 소멸 → 종료
		boolean[] alive = {true};
		EffectSystem.Effect att = fx.play("goEyeBeam", out -> {
			out.identity();
			return alive[0];
		});
		fx.update(1 / 60f, eye);
		assertFalse(att.done());
		alive[0] = false;
		fx.update(1 / 60f, eye);
		assertTrue(att.done());
	}

	/** 메두사 머리 GO (mewh): act "On"/"off" 를 이름으로 (40 문서 §4.1, 0x1970D0 / 0x196B18). */
	@Test
	void medusaHeadActs() throws Exception {
		EffectSystem fx = EffectSystem.headless();
		fx.add(wad("R_MEDHD0"));
		EffectLibrary.EffectDef head = fx.library().resolve("goMewH");
		assertNotNull(head);
		assertEquals("MDL_MEWH", head.model());
		assertEquals(15, head.skeleton().size());
		assertNotNull(head.act("On"));
		assertTrue(head.act("On").looping() && head.act("off").looping());
		EffectSystem.Effect e = fx.play("goMewH", out -> {
			out.identity();
			return true;
		}, Float.MAX_VALUE);
		assertTrue(e.setAct("Off"), "Hash127Upper 이므로 대소문자 무시");
		Vector3f eye = new Vector3f();
		for (int i = 0; i < 30; i++) {
			fx.update(1 / 60f, eye);
		}
		assertEquals(0f, e.rates()[0], 1e-6f, "Off: 이미터 꺼짐");
		e.setAct("On");
		assertEquals("On", e.actName());
		for (int i = 0; i < 60; i++) {
			fx.update(1 / 60f, eye);
		}
		assertFalse(e.done(), "머리는 첫 act(main 0.03초) 수명으로 끝나지 않는다");
	}

	/** SEM_ 이미터: 같은 이름 SEM_soundEmitter 가 여럿 → 오브젝트 그룹 앞의 정의. 이펙트 재생 = 슬롯 1 소리, 반복 이펙트는 stop 이 끈다. */
	@Test
	void effectSounds() throws Exception {
		EffectSystem fx = EffectSystem.headless();
		fx.add(wad("R_PERMK"));
		fx.add(wad("R_MEDHD0"));
		EffectLibrary lib = fx.library();
		assertEquals("SND_MEDUSA_RAY_LOOP", lib.resolve("goEyeBeam").sem().sound(1));
		assertEquals("SND_MEDUSA_RAY_SHOT", lib.resolve("goMedHeadFlash").sem().sound(1));
		assertEquals("SND_CHESTOPEN", lib.resolve("goChest").sem().sound(1));
		assertNotEquals(lib.resolve("goChest").sem().sounds(), lib.resolve("goStrikeGrp").sem().sounds(),
				"strikegrp 는 자기 그룹 앞의 SEM_soundEmitter");
		List<String> played = new ArrayList<>();
		List<Integer> stopped = new ArrayList<>();
		fx.sounds = new EffectSystem.Sounds() {
			public int play(String snd, Vector3f at, float near, float far) {
				played.add(snd);
				return played.size();
			}

			public void move(int h, Vector3f at) {
			}

			public void stop(int h) {
				stopped.add(h);
			}
		};
		EffectSystem.Effect beam = fx.play("goEyeBeam", EffectSystem.Anchor.at(new Vector3f(), 0));
		fx.play("goMedHeadFlash", EffectSystem.Anchor.at(new Vector3f(), 0));
		fx.update(1 / 60f, new Vector3f());
		assertEquals(List.of("SND_MEDUSA_RAY_LOOP", "SND_MEDUSA_RAY_SHOT"), played);
		beam.stop();
		assertEquals(List.of(1), stopped, "반복 광선 소리만 끈다");
	}

	/** 부서지는 물체 GO = 이펙트 GO 구성 (41 문서): ARENA06 항아리 파괴 애니 1.667초 + SEM SND_POTTERYSMASH, Brkb OrbEmitter. */
	@Test
	void breakableObjects() throws Exception {
		WadFile arena = wad("ARENA06");
		EffectLibrary lib = new EffectLibrary();
		lib.add(arena);
		EffectLibrary.EffectDef pot = lib.resolve("gopotlgdirtyabrk8");
		assertNotNull(pot);
		assertTrue(pot.oneShot());
		assertEquals(1.667f, pot.lifetime(), 1e-3f);
		assertNotNull(pot.model());
		assertEquals("SND_POTTERYSMASH", pot.sem().sound(1));
		Tweaks tw = new Tweaks();
		tw.load(wad("R_PERMK"));
		tw.load(arena);
		var b = new devple.gow.engine.game.Breakables(new devple.gow.tools.level.LevelData(arena), tw);
		assertTrue(b.items().stream().allMatch(i -> i.orbEmitter == -1 && i.fadeTime == 3f), "Brkb 000 \"No Orbs\": +0x28 = −1, Fade Time 3");
		WadFile a3 = wad("ATHN03A");
		Tweaks t3 = new Tweaks();
		t3.load(wad("R_PERMK"));
		t3.load(a3);
		var b3 = new devple.gow.engine.game.Breakables(new devple.gow.tools.level.LevelData(a3), t3);
		var barrel = b3.item("gobreakbarrel2");
		assertEquals(900, barrel.orbEmitter, "HydraBreakSmall → OrbEmitter 900 small obj break");
		assertEquals("small obj break", t3.s(barrel.orbNode(), "Instance Name", null));
		var orbs = devple.gow.engine.game.OrbDrops.rollNode(t3, barrel.orbNode(), new java.util.Random(1), 100, 100);
		assertFalse(orbs.isEmpty(), "Normal Weapon Prob 1 → Weapon Template 50 (붉은 오브)");
		assertEquals(2, orbs.getFirst().type());
		assertEquals(8f, b3.item("gobreakablewooddoor").hp, "Breakable5 Hit Points 8");
	}
}
