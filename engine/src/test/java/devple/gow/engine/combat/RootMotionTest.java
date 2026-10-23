package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원작 루트 모션 (0x1F7110 → 0x1EBD78, 37 문서 §11.4): 적·영웅 기술 중 zeroJoint 델타 = 이동·회전, 결합 처형의 이벤트 19/22 창은
 * 끄는 쪽이 상대를 0x204BF0 로 (일반 적 처형 §11.5, 아기 히드라 ATHN01C 821–825, 침대 ATHN01F 846–853).
 */
class RootMotionTest {

	private static final float DT = 1 / 60f;

	/** 영웅 + 적 하나 (적 WAD 의 영웅용 외부 애니 연결). */
	private record Rig(HeroController ctl, Combatant hero, EnemyFighter en, EnemyFactory.Kind kind, GrabSync sync,
					   List<Integer> heroMoves, List<Integer> enemyMoves) {
	}

	private static Rig rig(String wad, int ai1, String skeleton) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, wad)), "gow-rom 없음");
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
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
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, wad), perm, ai1, skeleton);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter.resetSpawnCount();
		EnemyFighter en = ai1 >= 0 ? k.spawn(ai1) : k.spawn();
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return false; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return c.takeHit(a, at, d); }
		});
		c.setTarget(en);
		List<Integer> hm = new ArrayList<>(), em = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				hm.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		en.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				em.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		return new Rig(ctl, c, en, k, new GrabSync(ctl, c), hm, em);
	}

	/** GameScene 과 같은 순서: 영웅 (결합 중이 아니면) → 적 → GrabSync. */
	private static void tick(Rig r, ScriptedInput in) {
		in.advance(DT);
		if (!r.sync().active()) {
			r.hero().update(in, DT);
			if (r.hero().active()) {
				r.ctl().updateCombat(r.hero(), in, 0, DT, r.en().pos);
			} else {
				r.ctl().update(in, 0, DT);
			}
		}
		r.en().update(DT);
		r.sync().update(in, r.en(), r.kind().tweaks(), DT);
	}

	/**
	 * 적 기술 루트 모션: 미노타우로스 공격 중 zeroJoint 변위가 가장 큰 기술 — 위치 변화 = R(요) · Δzero · 배율 (예전엔 제자리).
	 */
	@Test
	void enemyAttackMovesByZeroJoint() throws Exception {
		Rig r = rig("R_MIN.WAD", -1, null);
		EnemyFighter en = r.en();
		Skeleton sk = en.animator().skeleton();
		int zj = sk.indexOf("zeroJoint");
		int move = -1;
		Vector3f best = new Vector3f();
		for (int id : r.kind().attacks()) {
			if (!en.combat().forceMove(id) || en.animator().current() == null) {
				continue;
			}
			var a = en.animator().current();
			float[] z0 = a.sample(sk, 0, false).pos[zj], z1 = a.sample(sk, a.duration, false).pos[zj];
			Vector3f dz = new Vector3f(z1[0] - z0[0], 0, z1[2] - z0[2]);
			if (dz.length() > best.length()) {
				best = dz;
				move = id;
			}
		}
		Assumptions.assumeTrue(move >= 0 && best.length() > 10, "이동 공격 없음");
		en.pos.set(0, 0, -600); // 영웅에서 멀리 (적중 없음)
		en.yaw = 0.7f;
		Vector3f p0 = new Vector3f(en.pos);
		en.combat().forceMove(move);
		ScriptedInput in = new ScriptedInput(0, List.of());
		int serial = en.combat().moveSerial();
		for (int i = 0; i < 600 && en.combat().active() && en.combat().moveSerial() == serial; i++) {
			tick(r, in);
		}
		Vector3f want = new Vector3f(best).mul(en.scale).rotateY(0.7f);
		Vector3f got = new Vector3f(en.pos).sub(p0).setComponent(1, 0);
		assertTrue(got.distance(want) < 0.15f * want.length() + 3, "변위 " + got + " ≈ " + want + " (기술 " + move + ")");
	}

	/**
	 * 메두사 머리 잡기 MAIMEWGRAB: 이벤트 19 창이 처음에 잡은 자리의 어긋남을 끌어 맞춘 뒤 (0x204BF0) 영웅 zeroJoint 가 0 → 180° 돌아 영웅이
	 * 루트 회전으로 돌아서고 synchJoint 는 월드에 고정 (예전 엔진 대체: 고르곤을 영웅 위치·방향에 겹침).
	 */
	@Test
	void medusaGrabRootRotation() throws Exception {
		Rig r = rig("R_MEW.WAD", -1, null);
		EnemyFighter en = r.en();
		en.pos.set(0, 0, -40);
		en.yaw = (float) Math.PI; // 영웅 쪽
		en.hp = 1; // 잡기 원 아래
		List<ScriptedInput.Step> st = new ArrayList<>();
		st.add(new ScriptedInput.Step(0.2f, 0.05f, PadButton.CIRCLE));
		ScriptedInput in = new ScriptedInput(0, st);
		float yawAtLink = Float.NaN, maxTurn = 0;
		Vector3f enAtLink = null, synch0 = null;
		float synchDrift = 0;
		for (int i = 0; i < 60 * 8; i++) {
			tick(r, in);
			String ec = en.combat().clipName();
			if (r.sync().active() && "maiMewGrab".equalsIgnoreCase(ec)) {
				if (Float.isNaN(yawAtLink)) {
					yawAtLink = r.ctl().yaw;
					enAtLink = new Vector3f(en.pos);
				}
				maxTurn = Math.max(maxTurn, (float) Math.abs(Math.IEEEremainder(r.ctl().yaw - yawAtLink, Math.PI * 2)));
				Vector3f s = synchWorld(r.ctl());
				// 결합 처음 0.4 초쯤은 이벤트 19 끌기 (0x204BF0, 비율 0.1 → ×1.1) 가 잡은 자리의 어긋남을 메우므로 그 뒤부터 잰다
				if (synch0 == null && r.ctl().animator().time() > 0.5f) {
					synch0 = s;
				} else if (synch0 != null) {
					synchDrift = Math.max(synchDrift, new Vector3f(s).sub(synch0).setComponent(1, 0).length());
				}
			}
		}
		assertFalse(Float.isNaN(yawAtLink), "MAIMEWGRAB 결합: hero " + r.heroMoves() + " enemy " + r.enemyMoves());
		assertTrue(maxTurn > 2.2f, "영웅 루트 회전 (zeroJoint 0 → 180°, 실패 분기 전까지): " + Math.toDegrees(maxTurn));
		assertTrue(synchDrift < 6, "영웅 synchJoint 월드 고정: " + synchDrift);
		assertTrue(enAtLink.distance(r.ctl().position) > 10, "적을 영웅 위치에 겹치지 않음");
	}

	/** 영웅 synchJoint 월드 위치 (zeroJoint 기준 — 0x1F7110). */
	private static Vector3f synchWorld(HeroController ctl) {
		Matrix4f w = ctl.world(new Matrix4f(), 1f).mul(SyncPull.synchRel(ctl.animator(), 1f));
		return w.getTranslation(new Vector3f());
	}

	/**
	 * 아기 히드라 (ATHN01C AI1 252 "hydra enc2" → 스켈레톤 bhb): BITEGRAB(8) 적중 → 영웅 006 A821 (태그 821, 공격자 252) → 821 쌍 결합,
	 * 아기 히드라 821 의 이벤트 22 [0–0.90] 동안 영웅을 0x204BF0 관계로 끈다.
	 */
	@Test
	void babyHydraBitePullsHero() throws Exception {
		Rig r = rig("ATHN01C.WAD", 252, "hydra enc2");
		EnemyFighter en = r.en();
		assertEquals("bhb", r.kind().skeleton().name, "Instance Name 오브젝트가 없으면 CombatFileSet 애니로 스켈레톤");
		ScriptedInput in = new ScriptedInput(0, List.of());
		int pulls = 0;
		float err = Float.NaN;
		for (int attempt = 0; attempt < 12 && !r.heroMoves().contains(821); attempt++) {
			float dist = 30 + attempt * 15;
			en.pos.set(0, 0, -dist);
			en.yaw = (float) Math.PI;
			r.ctl().position.set(0, 0, 0);
			r.ctl().yaw = 0;
			en.combat().forceMove(8);
			for (int i = 0; i < 120 && !r.heroMoves().contains(821); i++) {
				tick(r, in);
			}
		}
		assertTrue(r.heroMoves().contains(821), "BITEGRAB 적중 → 영웅 821: " + r.heroMoves() + " 적 " + r.enemyMoves());
		for (int i = 0; i < 90; i++) {
			tick(r, in);
			if (r.sync().pulling() == 1) {
				pulls++;
				Matrix4f t = SyncPull.target(en.pos, en.yaw, en.animator(), en.scale, r.ctl().animator(), 1f);
				err = t.getTranslation(new Vector3f()).distance(r.ctl().position);
			}
		}
		assertTrue(r.sync().active(), "821 결합 (적 기술 플래그 0x20 · 같은 ID)");
		assertTrue(pulls > 30, "이벤트 22 창 동안 아기 히드라가 영웅을 끈다: " + pulls);
		assertTrue(err < 2, "0x204BF0 목표에 수렴: " + err);
	}

	/** ATHN01F 침대 (AI1 305 aoxpt): 846 MAIXPTBEDMOUNT 의 이벤트 22 [0–1] → 침대가 영웅을 끈다 (영웅 006 의 846). */
	@Test
	void bedMountPullsHero() throws Exception {
		Rig r = rig("ATHN01F.WAD", 305, "aoxpt");
		EnemyFighter bed = r.en();
		bed.pos.set(0, 0, -50);
		bed.yaw = 1.2f;
		bed.combat().forceMove(846);
		r.hero().forceMove(846);
		ScriptedInput in = new ScriptedInput(0, List.of());
		int pulls = 0;
		float err = Float.NaN;
		for (int i = 0; i < 60; i++) {
			tick(r, in);
			if (r.sync().pulling() == 1) {
				pulls++;
				Matrix4f t = SyncPull.target(bed.pos, bed.yaw, bed.animator(), bed.scale, r.ctl().animator(), 1f);
				err = t.getTranslation(new Vector3f()).distance(r.ctl().position);
			}
		}
		assertTrue(r.sync().active(), "MAIXPTBEDMOUNT 결합: hero " + r.heroMoves() + " bed " + r.enemyMoves());
		assertTrue(pulls > 40, "침대가 영웅을 끈다: " + pulls);
		assertTrue(err < 2, "0x204BF0 목표에 수렴: " + err);
	}

	/**
	 * ATHN01B 선창 히드라 (AI1 251 "hydra enc1" → bhh, 37 문서 §4.3): BHAenc1* 의 골반 X 축 180° (매달린 자세) 를 모델 Z 축 180° 로 바로 세워
	 * 머리가 뿌리보다 위·앞 (영웅 쪽) 에 오고, 대기 = Navigation ANIStand "BHAEnc1Idle" (예전 "main" 바인드 자세 = 위로 선 기둥),
	 * 걷기 클립이 없어 제자리 (예전 Sold 속도로 영웅 쪽 미끄러짐), 영웅 □ 콤보가 머리에 맞는다.
	 */
	@Test
	void holdHydraStandsUprightAnchoredAndIsHittable() throws Exception {
		Rig r = rig("ATHN01B.WAD", 251, "hydra enc1");
		EnemyFighter en = r.en();
		assertEquals("bhh", r.kind().skeleton().name);
		assertEquals("BHAenc1Idle", r.kind().idle(), "대기 = Navigation ANIStand");
		assertTrue(en.stationary, "걷기 클립 없음 → 제자리");
		// 원작 배치 비율: 뿌리 = 물속 noHero 바닥 (−268), 영웅 바닥 (−153) = 뿌리 + 115 (BHAenc1Grab 영웅 zeroJoint), 바닥 끝 = 앞 230–280
		en.pos.set(0, -115, 0);
		en.yaw = (float) Math.PI;
		r.ctl().position.set(0, 0, 270);
		r.ctl().yaw = 0;
		Skeleton sk = en.animator().skeleton();
		int tip = sk.indexOf("BHA01JOCTIP21");
		ScriptedInput idle = new ScriptedInput(0, List.of());
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		for (int i = 0; i < 600; i++) {
			tick(r, idle);
			if (i % 10 == 0 && en.combat().active()) {
				Matrix4f m = en.world(new Matrix4f(), 1f).mul(new Matrix4f().set(en.animator().pose().world(sk)[tip]));
				Vector3f p = m.getTranslation(new Vector3f());
				minY = Math.min(minY, p.y);
				maxY = Math.max(maxY, p.y);
				minZ = Math.min(minZ, p.z);
				maxZ = Math.max(maxZ, p.z);
			}
		}
		assertTrue(minY > -40 && maxY < 80, "턱 끝이 영웅 높이 (바닥 0 기준): " + minY + ".." + maxY);
		assertTrue(minZ > 150 && maxZ < 360, "턱 끝이 앞 (영웅 쪽 +Z): " + minZ + ".." + maxZ);
		assertEquals(0, Math.hypot(en.pos.x, en.pos.z), 1e-3, "뿌리 고정");
		assertTrue(r.enemyMoves().stream().anyMatch(m -> m == 15 || m == 16), "DTre_251 물기 15/16: " + r.enemyMoves());
		// 영웅 □ 연타 → 머리 적중 (영웅 CDV + 블레이드 — 뿌리가 270 떨어져 정면 구 근사로는 안 닿는다)
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		Skeleton hsk = r.ctl().animator().skeleton();
		r.hero().setHitVolumes(HitVolumes.forHero(heroWad, hsk, WadFile.load(Content.file(rom, "R_WPN0_2.WAD"))));
		float hp0 = en.hp;
		List<ScriptedInput.Step> steps = new ArrayList<>();
		for (int k = 0; k < 40; k++) {
			steps.add(new ScriptedInput.Step(0.1f + k * 0.25f, 0.05f, PadButton.SQUARE));
		}
		ScriptedInput sq = new ScriptedInput(0, steps);
		for (int i = 0; i < 600 && en.hp >= hp0; i++) {
			r.ctl().position.set(0, 0, 250);
			tick(r, sq);
		}
		assertTrue(en.hp < hp0, "□ 콤보가 히드라에 맞는다: " + en.hp + " / " + hp0 + " 영웅 " + r.heroMoves());
	}
}
