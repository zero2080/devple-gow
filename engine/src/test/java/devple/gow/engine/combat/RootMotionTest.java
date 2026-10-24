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
		en.holdAi = true; // 기술 중 자동 조준 (0x2077A0 — 대상 쪽으로 10 %/프레임, 50 문서 §6) 이 방향을 바꾸지 않게 AI 정지 (루트 모션만 본다)
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
	 * ATHN01B 선창 히드라 (AI1 251 "hydra enc1" → bhh, 37 문서 §4.3): BHAenc1* 의 골반 이동 (디스크립터 1) 으로 머리가 뿌리보다 위·앞 (영웅 쪽) 에
	 * 오고 (보정 없음), 대기 = Navigation ANIStand "BHAEnc1Idle" (예전 "main" 바인드 자세 = 위로 선 기둥),
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
		// 대기 턱 끝 = 뿌리 앞 ≈ 300 (0.3 × 996), 물기 Bite2x 는 골반을 앞으로 옮기며 (디스크립터 1, z +51) 뿌리 앞 0.3 × 1639 ≈ 492 까지 내민다
		// — 바닥 끝 (뿌리 + 230–280) 너머 선창 위의 영웅에게 닿는다 (예전 항목 번호 배치 + X 50.7° 들어 올림은 360 안)
		assertTrue(minZ > 150 && maxZ < 520, "턱 끝이 앞 (영웅 쪽 +Z): " + minZ + ".." + maxZ);
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
		for (int i = 0; i < 300; i++) {
			r.ctl().position.set(0, 0, 250);
			tick(r, sq);
		}
		assertEquals(0, Math.hypot(en.pos.x, en.pos.z), 1e-3, "맞아도 뿌리가 밀리지 않는다 (예전 넉백으로 150 초에 84 물러남)");
	}

	/**
	 * 선창 히드라 머리는 모든 BHAenc1* 클립에서 바로 선다 (사용자 보고 "히드라 뒤집힘") — 보정 없이: BHAenc1* 의 골반은 X 축 180° (목이 아래로) 이지만
	 * 디스크립터 1 (타입 0 이동, 05 §2.8.1) 의 절대 키가 골반을 뿌리 위 (77, 842.7, −106.7) 에 둬 대기 턱 끝이 뿌리 위 +421 · 앞 −996 (배율 0.3: +126 · 300) 에 온다.
	 * 예전 항목 번호 배치는 이 이동을 읽지 않아 머리가 바닥 아래로 매달렸고 (턱 끝 (9, −421, −889)), 엔진이 골반 앞 X 축 50.7° 회전 (hangLift) 으로
	 * 그 자리를 흉내냈다 — 그 회전의 턱 끝 y +421 이 바로 골반 이동 842.7 − 목 421.3 이다 [확실: 데이터]. 830 BHAenc1Grab 동안 영웅 손목 ↔ 히드라 머리
	 * (머리 관절·두 눈·턱 끝 중 가까운 것) 평균 거리도 새 배치 64.5 (예전 X 50.7° 108, 무보정 259).
	 * 두 눈 (JOLEYE11/JOREYE11) 은 턱 끝 (TIP21) 보다 위 — 아기 히드라 bhb (같은 리그·메시, 원작과 대조된 자세) BHAbabyEmerge 끝 자세와 같은 부호.
	 */
	@Test
	void holdHydraHeadUprightInEveryClip() throws Exception {
		Rig r = rig("ATHN01B.WAD", 251, "hydra enc1");
		EnemyFighter en = r.en();
		Skeleton sk = en.animator().skeleton();
		int pel = sk.indexOf(EnemyFighter.HYDRA_ROOT), tip = sk.indexOf(EnemyFighter.HYDRA_JAW_TIP);
		int le = sk.indexOf("JOLEYE11"), re = sk.indexOf("JOREYE11"), head = sk.indexOf("BHA01JOCHEA1");
		var idle = en.animator().find("BHAenc1Idle");
		var p0 = idle.sample(sk, 0, false);
		assertArrayEquals(new float[]{77.15f, 842.68f, -106.67f}, p0.pos[pel], 0.05f, "골반 = 디스크립터 1 절대 키");
		float[] t0 = p0.world(sk)[tip];
		assertEquals(421.4f, t0[13], 1f, "대기 턱 끝 = 뿌리 위 (골반 842.7 − 목 421.3)");
		assertEquals(-995.6f, t0[14], 1f, "대기 턱 끝 = 뿌리 앞");
		// 머리 위쪽 = (머리 관절 → 턱 끝) × (오른눈 → 왼눈) 의 부호를 같은 세트의 매달리지 않은 (골반 ≈ 단위) BHAbabyEmerge 끝 자세로 정한다
		en.animator().play("BHAbabyEmerge", true);
		en.animator().update(1f);
		en.animator().seek(en.animator().current().duration);
		float ref = headUp(en.animator().pose().world(sk), head, tip, le, re);
		assertTrue(Math.abs(ref) > 0.3f, "기준 머리 위쪽 " + ref);
		int clips = 0;
		for (var g : r.kind().anims().groups) {
			for (var a : g.acts) {
				if (!a.name.startsWith("BHAenc1") || !a.hasSkeletalData()) {
					continue;
				}
				assertTrue(EnemyFighter.hangsUpsideDown(sk, a, pel), a.name);
				en.animator().play(a.name, true);
				en.animator().update(1f); // 전환 끝 (앞 클립과 섞지 않음)
				for (int k = 0; k <= 8; k++) {
					// Grab 끝 (0.83–1, EV_DEATH 150) 은 목을 뒤로 젖혀 물러나는 구간이라 제외
					float t = a.duration * k / 8f * (a.name.equals("BHAenc1Grab") ? 0.8f : 1f);
					en.animator().seek(t);
					float[][] w = en.animator().pose().world(sk);
					float up = headUp(w, head, tip, le, re) * Math.signum(ref);
					// 잡기 830 · 실패 831–836 은 영웅과 씨름하며 머리를 비튼다 (원본 자세 그대로 Grab 3 초 −0.05, GrabFail3/4 최저 −0.29) — 자리만 본다
					if (!a.name.contains("Grab")) {
						assertTrue(up > 0f, a.name + " t" + t + ": 머리 위쪽 y " + up);
					}
					assertTrue(w[tip][13] > 300, a.name + " t" + t + ": 턱 끝이 뿌리 위 " + w[tip][13]);
				}
				clips++;
			}
		}
		assertTrue(clips >= 19, "BHAenc1 클립 " + clips);
	}

	/** 머리 위쪽 단위 벡터의 y: (턱 끝 − 머리 관절) × (왼눈 − 오른눈), 모델 공간. */
	private static float headUp(float[][] w, int head, int tip, int le, int re) {
		Vector3f s = new Vector3f(w[tip][12] - w[head][12], w[tip][13] - w[head][13], w[tip][14] - w[head][14]);
		Vector3f l = new Vector3f(w[le][12] - w[re][12], w[le][13] - w[re][13], w[le][14] - w[re][14]);
		return s.cross(l).normalize().y;
	}

	/**
	 * 선창 히드라 ○ 마무리 (37 문서 §4.3): 기절 루프 107 (SET_1B0 830) 에 영웅 ○ GRAB 200 의 HIT_CONFIRM 은 항상 830 BHAENC1GRAB —
	 * 같은 점수의 204 GRABREJECT (tag 830, 플래그 0x4000 = 태그 부정, 0x208578) 는 태그가 같으면 실패. 예전엔 0x4000 을 "태그 무시" 로 읽어
	 * 동점 무작위로 GRABREJECT 가 자주 나왔다. 830 의 SELF_HP 로 죽으면 EV_DEATH 는 (block) → 830 이 끝난 뒤 루트 A150 EV_DEATH →
	 * 150 BHAENC1GRAB @0.83 (0x208A98 끝의 매 프레임 사망 검사, 1035 150 → playerBlock-DetectDeath).
	 */
	@Test
	void holdHydraCircleFinisherAndDeathMove() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		for (int run = 0; run < 6; run++) {
			Rig r = rig("ATHN01B.WAD", 251, "hydra enc1");
			EnemyFighter en = r.en();
			WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
			r.hero().setHitVolumes(HitVolumes.forHero(heroWad, r.ctl().animator().skeleton(), WadFile.load(Content.file(rom, "R_WPN0_2.WAD"))));
			en.pos.set(0, -115, 0);
			en.yaw = (float) Math.PI;
			r.ctl().position.set(0, 0, 300);
			r.ctl().yaw = 0;
			en.hp = 150;
			assertTrue(en.combat().request(107));
			ScriptedInput idle = new ScriptedInput(0, List.of());
			for (int i = 0; i < 20 + run * 7; i++) {
				tick(r, idle);
			}
			assertEquals(830, en.combat().stanceTag(), "기절 루프 SET_1B0 830");
			ScriptedInput ci = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.05f, 0.1f, PadButton.CIRCLE)));
			for (int i = 0; i < 90; i++) {
				tick(r, ci);
			}
			assertTrue(r.heroMoves().contains(830) && !r.heroMoves().contains(204), "○ → 830, GRABREJECT 아님: " + r.heroMoves());
			assertEquals(830, en.combat().move().id(), "히드라 830: " + r.enemyMoves());
		}
		// 사망: 830 중 SELF_HP → (block) → 끝난 뒤 150
		Rig r = rig("ATHN01B.WAD", 251, "hydra enc1");
		EnemyFighter en = r.en();
		en.pos.set(0, -115, 0);
		r.ctl().position.set(0, 0, 100000); // 영웅은 멀리 (동기·피격 없음)
		assertTrue(en.combat().forceMove(830));
		ScriptedInput idle = new ScriptedInput(0, List.of());
		for (int i = 0; i < 60; i++) {
			tick(r, idle);
		}
		en.selfHp(en.hp);
		assertTrue(en.dead());
		for (int i = 0; i < 60 * 16 && !r.enemyMoves().contains(150); i++) {
			tick(r, idle);
		}
		assertEquals(List.of(830, 150), r.enemyMoves().subList(r.enemyMoves().size() - 2, r.enemyMoves().size()), "830 끝 → 150: " + r.enemyMoves());
		assertTrue(en.animator().time() >= 0.83f * en.animator().current().duration - 0.1f, "150 은 @0.83 부터: " + en.animator().time());
		for (int i = 0; i < 60 * 5; i++) {
			tick(r, idle);
		}
		assertEquals(1, r.enemyMoves().stream().filter(m -> m == 150).count(), "사망 기술은 한 번만: " + r.enemyMoves());
	}
}
