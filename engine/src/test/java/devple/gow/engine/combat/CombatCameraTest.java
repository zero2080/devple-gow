package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전투 카메라 이벤트 23 (30 문서 §9.3): 이벤트 해시 = Hash127Upper(접두사), act = 접두사 + "0", "1" … (ANM_camera 의 적 WAD 외부 그룹),
 * 지금 카메라 쪽 변형 고르기 (0x2054A0), 몸 회전으로 CamPos 가 눈 쪽으로 (0x205280). 미노타우로스 처형 (GrabTest 와 같은 하네스).
 */
class CombatCameraTest {

	@Test
	void actHashesAndVariants() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_MIN.WAD")), "gow-rom 없음");
		CombatCamera cc = new CombatCamera(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		cc.addWad(WadFile.load(Content.file(rom, "R_MIN.WAD")));
		cc.addWad(WadFile.load(Content.file(rom, "R_CY2.WAD")));
		assertEquals(0xd485fc51, CombatCamera.hashUpper("maiMin01Cam"), "M400 이벤트 23 해시");
		assertEquals(List.of("maiMin01Cam0", "maiMin01Cam1"), cc.variants(0xd485fc51).stream().map(a -> a.name).toList());
		assertEquals(List.of("maiMin03Cam0"), cc.variants(0xd4c47f4f).stream().map(a -> a.name).toList());
		assertEquals(List.of("MAICYWMini02Cam0"), cc.variants(0xad15316a).stream().map(a -> a.name).toList(), "사이클롭스 M240");
		assertTrue(cc.variants(0x12345678).isEmpty());
	}

	@Test
	void minotaurExecutionCamera() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_MIN.WAD")), "gow-rom 없음");
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		Combatant c = new Combatant(cd, an, owner);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "R_MIN.WAD"), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter.resetSpawnCount();
		EnemyFighter en = k.spawn();
		en.pos.set(0, 0, -50);
		en.hp = 20;
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		c.setTarget(en);
		List<ScriptedInput.Step> st = new ArrayList<>();
		st.add(new ScriptedInput.Step(0.2f, 0.05f, PadButton.CIRCLE));
		for (int i = 0; i < 60; i++) {
			st.add(new ScriptedInput.Step(4.5f + i * 0.12f, 0.05f, PadButton.CIRCLE));
		}
		ScriptedInput in = new ScriptedInput(0, st);
		GrabSync sync = new GrabSync(ctl, c);
		CombatCamera cc = new CombatCamera(perm);
		cc.addWad(k.assets().wad());
		Vector3f eye = new Vector3f(150, 60, 40); // 레벨 카메라 눈 (고정)
		Set<String> acts = new LinkedHashSet<>();
		float w = 0, minGap = 180;
		int frames = 0;
		float dt = 1 / 60f;
		for (float t = 0; t < 14f; t += dt) {
			in.advance(dt);
			if (!sync.active()) {
				c.update(in, dt);
				if (c.active()) {
					ctl.updateCombat(c, in, 0, dt, en.pos);
				} else {
					ctl.update(in, 0, dt);
				}
			}
			en.update(dt);
			sync.update(in, en, k.tweaks(), dt);
			if (!sync.active()) {
				ctl.separate(en.pos, 22);
			}
			float[] y = {en.yaw};
			boolean on = cc.update(en, en.combat(), en.pos, y, en.animator(), en.scale, eye, w);
			en.yaw = y[0];
			if (!on) {
				w = 0;
				continue;
			}
			w = Math.min(1, w + 0.01f); // 0x1F4718
			frames++;
			acts.add(cc.actName());
			CombatCamera.Frame f = cc.frame();
			assertTrue(f.radius() > 10 && f.radius() < 120, "줌 반경 " + f.radius());
			assertTrue(f.aim().distance(en.pos) < 200, "AimJoint 가 미노타우로스 근처: " + f.aim() + " / " + en.pos);
			if ("maiMin01Cam0".equals(cc.actName()) || "maiMin01Cam1".equals(cc.actName())) {
				Vector3f a = new Vector3f(eye).sub(en.pos).setComponent(1, 0).normalize();
				Vector3f b = new Vector3f(f.camPos()).sub(en.pos).setComponent(1, 0).normalize();
				minGap = Math.min(minGap, (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b))))));
			}
		}
		assertTrue(frames > 120, "이벤트 23 창 프레임 " + frames);
		assertTrue(acts.contains("maiMin03Cam0"), "성공 402 의 카메라: " + acts);
		assertTrue(acts.stream().anyMatch(s -> s.startsWith("maiMin01Cam")), "400 의 변형: " + acts);
		assertTrue(minGap < 5, "몸 회전 (0x205280) 으로 저작된 CamPos 방향이 눈 쪽으로: " + minGap + "°");
		assertNull(cc.owner(), "창이 끝나면 해제");
	}
}
