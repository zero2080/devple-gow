package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 잡기 처형의 시작 관계 (37 문서 §11.5): 원작은 이벤트 19 처형에서 위치를 맞추지 않는다 — GRAB 이 처음 닿은 자리 + 루트 모션.
 * 적 종류마다 설계 관계 (처형 0 프레임 synchJoint 일치) 가 나오는 ○ 시작 거리가 GRAB 의 사거리 안에 있고, 그 자리에서 잡으면 오차가 작다.
 * 대부분은 GRAB 의 extra1 구 (부위 9, [0.27–0.32]) 가 닿는 거리 = 설계 관계 (멀리서 누르면 달려들어 이 창에서 잡는다).
 */
class GrabRelationTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_MIN.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 같은 데이터로 매번 새 영웅·적을 만드는 공급자. */
	private static Supplier<GrabRelation.Trial> trials(String wad) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		WadFile wpn = WadFile.load(rom.resolve("R_WPN0_1.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve(wad), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		return () -> {
			EnemyFighter.resetSpawnCount();
			return GrabRelation.Trial.create(sk, set, tw, cd, () -> HitVolumes.forHero(heroWad, sk, wpn), k);
		};
	}

	private record Case(String wad, int finisher, float design, boolean extra1) {
	}

	/**
	 * 적 종류별 시작 거리: 설계 관계 거리 (±1), 처형 기술, 찾은 자리에서 오차 &lt; 2, 결합 거리 ≈ 설계 (±8).
	 * extra1 = 적중이 GRAB 부위 9 창 [0.27–0.32] 에서 (멀리서 달려들어 잡음).
	 */
	@Test
	void designedStartPerEnemy() throws Exception {
		List<Case> cases = List.of(
				new Case("R_MIN.WAD", 400, 70.4f, true),   // 미노타우로스 MAIMIN01
				new Case("R_MEW.WAD", 260, 37.7f, true),   // 고르곤 MAIMEWGRAB
				new Case("R_ME2.WAD", 263, 37.7f, true),   // 고르곤 2 MAIMEWGRAB
				new Case("R_SIR.WAD", 421, 45.9f, true),   // 세이렌 MAISIRKILL (HP 5)
				new Case("R_SAT.WAD", 431, 37.4f, true),   // 사티로스 MAISATGRAB
				new Case("R_SKS.WAD", 220, 27.6f, false),  // 해골병 (레기오네어) MAISKB00
				new Case("R_CNM.WAD", 291, 40.6f, true),   // 켄타우로스 MAICNMKILL
				new Case("R_CEP.WAD", 286, 42.2f, true),   // 케르베로스 새끼 MAICEPSTOMP
				new Case("R_CEA.WAD", 395, 43.3f, false)); // 케르베로스 MAICEAKILL
		StringBuilder log = new StringBuilder();
		for (Case c : cases) {
			Assumptions.assumeTrue(Files.exists(rom().resolve(c.wad())));
			GrabRelation.Result r = GrabRelation.search(trials(c.wad()), 10, 130);
			assertNotNull(r, c.wad());
			log.append(String.format("%s %s%n", c.wad(), r));
			assertEquals(c.finisher(), r.finisher(), c.wad());
			assertEquals(c.design(), r.design(), 1f, c.wad() + " 설계 관계");
			assertTrue(r.error() < 2f, c.wad() + " 오차: " + r);
			assertEquals(r.design(), r.link(), 8f, c.wad() + " 결합 거리 ≈ 설계 관계: " + r);
			assertTrue(r.start() > r.link(), c.wad() + " GRAB 루트 모션으로 달려들어 잡음: " + r);
			if (c.extra1()) {
				assertTrue(r.hitProgress() >= 0.27f && r.hitProgress() <= 0.33f, c.wad() + " 부위 9 창에서 적중: " + r);
			}
		}
		System.out.print(log);
	}

	/** 원작에는 맞춤이 없다: 너무 가까이서 누르면 바로 닿고 관계가 그만큼 어긋난다 (오차 ≈ 거리 차이). */
	@Test
	void tooCloseKeepsHitPosition() throws Exception {
		Supplier<GrabRelation.Trial> t = trials("R_MIN.WAD");
		GrabRelation.Result best = GrabRelation.search(t, 10, 130);
		GrabRelation.Result close = GrabRelation.run(t.get(), 40);
		assertTrue(close.hitProgress() < 0.12f, "가까우면 GRAB 첫 판정 (부위 1 [0.05–]) 에서 바로 잡힘: " + close);
		assertTrue(close.error() > 20 && close.error() > best.error() + 20, "정렬 없음 → 잡은 자리 그대로: " + close + " / " + best);
		assertEquals(close.design() - close.link(), close.error(), 6f, "오차 ≈ 설계 관계 − 결합 거리");
	}

	/**
	 * 몸 캡슐 (Sold +0x234) 이 설계 거리보다 큰 처형 (37 문서 §11.5): 사이클롭스 (캡슐 합 57.6 &gt; 설계 29.7), 하피 (19.6 &gt; 7.9, 뒤에서).
	 * <ul>
	 *   <li>밀기를 빼면 (비교용) GRAB 첫 접촉 = 설계 관계 — 다른 처형과 같이 오차 &lt; 1 (설계가 GRAB 달려듦에 맞춰져 있다).</li>
	 *   <li>원작 밀기 규칙 [확실: 0x1E2DD8 / 0x1E28B0 / 0x1E6DF0 — GRAB stateMask 7 = 단단함, 잡기 원 상태·동기 연결 +0x84 와 무관,
	 *       밀기 끔은 Sold +0x1C 비트 1 (둘 다 0) 과 BMI Think 뿐] 으로는 GRAB 이 닿기 전에 서로 밀어 설계 관계에 못 간다:
	 *       사이클롭스는 캡슐 경계 (결합 ≥ 45), 하피는 날기 (0x40 → 지상 영웅은 안 밀림) 지만 하피가 자기를 민다 (결합 ≥ 13).</li>
	 * </ul>
	 */
	@Test
	void capsuleLimitedFinishers() throws Exception {
		for (String w : new String[]{"R_CY2.WAD", "R_HAR.WAD"}) {
			Assumptions.assumeTrue(Files.exists(rom().resolve(w)));
			Supplier<GrabRelation.Trial> t = trials(w);
			GrabRelation.Result free = GrabRelation.search(t, 5, 130, false);
			assertNotNull(free, w);
			assertTrue(free.error() < 1f, w + " 밀기 없으면 설계 관계: " + free);
			GrabRelation.Result pushed = GrabRelation.search(t, 5, 130, true);
			assertTrue(pushed.link() > free.link() + 5, w + " 원작 밀기로 설계 거리에 못 감: " + pushed + " / " + free);
			assertTrue(pushed.error() > 5f, w + ": " + pushed);
			if (w.equals("R_CY2.WAD")) {
				assertTrue(pushed.link() >= 45f, "사이클롭스 캡슐 경계 (영웅 쪽 수평 45.6, 사이클롭스 쪽 56.8): " + pushed);
			} else {
				assertTrue(pushed.link() >= 13f, "하피가 자기를 민다 (하피 쪽 수평 17.3): " + pushed);
			}
		}
	}

	/**
	 * 적 수직 루트 모션 (0x1EBD78 → stateMask &amp; 0x1C == 0 기술은 0x1ECD60 경로: 중력 없는 3D 변위, 바닥 아래만 막음): zeroJoint 가 위로 가는
	 * 자유 이동 기술은 그만큼 오른다 (예전: 수직 버림). stateMask 7 기술은 지상 이동이 붙이므로 그대로.
	 */
	@Test
	void enemyVerticalRootMotionInFreeMoves() throws Exception {
		boolean tested = false;
		for (String w : new String[]{"R_SAT.WAD", "R_CY2.WAD", "R_HAR.WAD", "R_MIN.WAD", "R_SKS.WAD"}) {
			Supplier<GrabRelation.Trial> t = trials(w);
			GrabRelation.Trial probe = t.get();
			EnemyFighter en = probe.enemy();
			Skeleton sk = en.animator().skeleton();
			int zj = sk.indexOf("zeroJoint");
			int best = -1;
			float bestRise = 0;
			for (int id = 0; id < 1000; id++) {
				CombatFile.Move m = en.combat().data().move(id);
				if (m == null || (m.stateMask() & 0x1C) != 0 || !en.combat().forceMove(id) || en.animator().current() == null) {
					continue;
				}
				var a = en.animator().current();
				float y0 = a.sample(sk, 0, false).pos[zj][1], peak = y0;
				for (float s = 0; s <= a.duration; s += 1 / 30f) {
					peak = Math.max(peak, a.sample(sk, s, false).pos[zj][1]);
				}
				if (peak - y0 > bestRise) {
					bestRise = peak - y0;
					best = id;
				}
			}
			if (best < 0 || bestRise < 15) {
				continue;
			}
			GrabRelation.Trial tr = t.get();
			EnemyFighter e = tr.enemy();
			e.pos.set(0, 0, -800);
			e.combat().forceMove(best);
			int serial = e.combat().moveSerial();
			var a = e.animator().current();
			float y0 = a.sample(sk, 0, false).pos[zj][1];
			float peakY = 0, worst = 0;
			for (int i = 0; i < 600 && e.combat().active() && e.combat().moveSerial() == serial; i++) {
				e.update(1 / 60f);
				if (e.combat().moveSerial() != serial) {
					break;
				}
				float tm = Math.min(e.animator().time(), a.duration);
				float want = e.scale * (a.sample(sk, tm, false).pos[zj][1] - y0);
				worst = Math.max(worst, Math.abs(e.pos.y - want));
				peakY = Math.max(peakY, e.pos.y);
			}
			assertTrue(peakY > 10, w + " 기술 " + best + " 이 오름: " + peakY);
			// 첫 프레임은 직전 델타 유지 (0x1F7110 카운터 < 2) → 한 프레임 늦음
			assertTrue(worst < 6f, w + " 기술 " + best + ": 높이 = 배율 × (zero y − 처음), 최대 차이 " + worst);
			tested = true;
			break;
		}
		Assumptions.assumeTrue(tested, "zeroJoint 가 오르는 자유 이동 기술 없음");
	}

	/**
	 * 결합 없는 끌기의 끄는 쪽 = 공격 대상이 영웅인 적 (0x2097A8: 플래그 0x20 기술 시작 때 +0x84 = +0x80) — 가장 가까운 적이 아니다.
	 * 아기 히드라 두 마리: 가까운 쪽은 대기, 먼 쪽이 825 BHABABYGRAB [0–0.22] → 먼 쪽이 영웅을 끈다.
	 */
	@Test
	void lonePullUsesAttackTargetNotNearest() throws Exception {
		Path rom = rom();
		Assumptions.assumeTrue(Files.exists(rom.resolve("ATHN01C.WAD")));
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve("ATHN01C.WAD"), perm, 252, "hydra enc2");
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		GrabRelation.Trial tr = GrabRelation.Trial.create(sk, set, tw, cd, null, k);
		EnemyFighter near = tr.enemy();
		EnemyFighter far = k.spawn(252);
		far.holdAi = true;
		far.setFoe(new Hittable() {
			public Vector3f position() { return tr.ctl().position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return tr.hero().stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		near.pos.set(0, 0, -40);
		far.pos.set(0, 0, 120);
		far.yaw = 0;
		assertTrue(far.combat().forceMove(825), "825 BHABABYGRAB");
		GrabSync sync = new GrabSync(tr.ctl(), tr.hero());
		List<EnemyFighter> all = new ArrayList<>(List.of(near, far));
		sync.setPullers(() -> all);
		ScriptedInput in = new ScriptedInput(0, List.of());
		int pulls = 0;
		float err = Float.NaN;
		for (int i = 0; i < 20; i++) {
			in.advance(1 / 60f);
			tr.ctl().update(in, 0, 1 / 60f);
			near.update(1 / 60f);
			far.update(1 / 60f);
			sync.update(in, near, tr.enemyTweaks(), 1 / 60f); // 대상 = 가까운 적 (GameScene nearestEnemy)
			if (sync.pulling() == 1) {
				pulls++;
				Matrix4f t = SyncPull.target(far.pos, far.yaw, far.animator(), far.scale, tr.ctl().animator(), 1f);
				err = t.getTranslation(new Vector3f()).distance(tr.ctl().position);
			}
		}
		assertTrue(pulls > 5, "먼 아기 히드라 (공격 대상 = 영웅, 825 창) 가 끈다: " + pulls);
		assertTrue(tr.ctl().position.z > 20, "영웅이 먼 쪽으로 끌려감: " + tr.ctl().position + " 오차 " + err);
	}

	/**
	 * 끌리는 쪽은 끌리는 첫 프레임부터 루트 모션 없음 (원작 0x1EBF8C: 상대 이벤트 창을 같은 프레임에 0x206030 으로 갱신한 뒤 0x2441E0 → D = 단위).
	 * 엔진은 두 쪽 갱신 뒤에 끌기를 알게 되므로 그 틱의 루트 모션을 되돌린다 (BossSync·GrabSync): 되돌리면 이동·요가 갱신 전과 같다.
	 */
	@Test
	void undoRootMotionRestoresPose() throws Exception {
		GrabRelation.Trial t = trials("R_MIN.WAD").get();
		EnemyFighter en = t.enemy();
		en.pos.set(0, 0, -600);
		en.yaw = 0.4f;
		int move = -1;
		for (int id : new int[]{10, 11, 12, 13, 14, 15, 16}) {
			if (en.combat().forceMove(id)) {
				move = id;
				break;
			}
		}
		Assumptions.assumeTrue(move >= 0);
		float moved = 0;
		for (int i = 0; i < 120 && moved < 1; i++) {
			Vector3f p0 = new Vector3f(en.pos);
			float y0 = en.yaw;
			en.update(1 / 60f);
			moved = new Vector3f(en.pos).sub(p0).length();
			if (moved >= 1) {
				en.undoRootMotion();
				assertEquals(0, new Vector3f(en.pos).distance(p0), 1e-3f, "적 루트 모션 되돌림 (기술 " + move + ")");
				assertEquals(y0, en.yaw, 1e-5f);
			}
		}
		assertTrue(moved >= 1, "루트 모션이 있는 공격 기술");
		var ctl = t.ctl();
		t.hero().forceMove(GrabRelation.GRAB_MOVE); // GRAB: zero z 1.8 → −30.7 (앞으로)
		for (int i = 0; i < 10; i++) {
			ctl.updateCombat(t.hero(), new ScriptedInput(0, List.of()), 0, 1 / 60f, null);
		}
		Vector3f h0 = new Vector3f(ctl.position);
		ctl.updateCombat(t.hero(), new ScriptedInput(0, List.of()), 0, 1 / 60f, null);
		assertTrue(ctl.position.distance(h0) > 0.5f, "GRAB 루트 모션");
		ctl.undoRoot();
		assertEquals(0, ctl.position.distance(h0), 1e-3f, "영웅 루트 모션 되돌림");
	}
}
