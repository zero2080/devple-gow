package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
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
 * 잡기 처형의 시작 관계 (37 문서 §11.5): 처형 기술의 이벤트 19 (FLAG_4) 창이 +0x1AC |= 0x4 → 0x2441E0(+0x1AC, 0x24) → 0x204BF0 으로
 * 동기 상대를 설계 관계 (처형 0 프레임 synchJoint 일치) 로 끌어 붙인다 — 어디서 잡든 0.5 초 뒤 오차 &lt; 1.
 * 끌기 전 GRAB 첫 접촉 자리도 대부분 설계 관계와 같다: GRAB 의 extra1 구 (부위 9, [0.27–0.32]) 가 닿는 거리 (멀리서 누르면 달려들어 이 창에서 잡는다).
 */
class GrabRelationTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_MIN.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 같은 데이터로 매번 새 영웅·적을 만드는 공급자. */
	private static Supplier<GrabRelation.Trial> trials(String wad) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		WadFile wpn = WadFile.load(Content.file(rom, "R_WPN0_1.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, wad), perm, -1);
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
	 * 적 종류별 시작 거리: 설계 관계 거리 (±1), 처형 기술, 찾은 자리에서 결합 순간 (끌기 전) 오차 &lt; 2, 결합 거리 ≈ 설계 (±8), 0.5 초 뒤 오차 &lt; 1.
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
			assertTrue(r.contact() < 2f, c.wad() + " 결합 순간 오차: " + r);
			assertTrue(r.error() < 1f, c.wad() + " 0.5 초 뒤 오차: " + r);
			assertEquals(r.design(), r.link(), 8f, c.wad() + " 결합 거리 ≈ 설계 관계: " + r);
			assertTrue(r.start() > r.link(), c.wad() + " GRAB 루트 모션으로 달려들어 잡음: " + r);
			if (c.extra1()) {
				assertTrue(r.hitProgress() >= 0.27f && r.hitProgress() <= 0.33f, c.wad() + " 부위 9 창에서 적중: " + r);
			}
		}
		System.out.print(log);
	}

	/**
	 * 너무 가까이서 누르면 바로 닿아 결합 순간 관계가 그만큼 어긋나지만 (오차 ≈ 거리 차이), 처형 기술의 이벤트 19 창 (미노타우로스 400 MAIMIN01)
	 * 이 0x204BF0 으로 끌어 0.5 초 뒤엔 설계 관계가 된다.
	 */
	@Test
	void tooCloseIsPulledIntoRelation() throws Exception {
		Supplier<GrabRelation.Trial> t = trials("R_MIN.WAD");
		GrabRelation.Result best = GrabRelation.search(t, 10, 130);
		GrabRelation.Result close = GrabRelation.run(t.get(), 40);
		assertTrue(close.hitProgress() < 0.12f, "가까우면 GRAB 첫 판정 (부위 1 [0.05–]) 에서 바로 잡힘: " + close);
		assertTrue(close.contact() > 20 && close.contact() > best.contact() + 20, "결합 순간은 잡은 자리: " + close + " / " + best);
		assertEquals(close.design() - close.link(), close.contact(), 6f, "결합 순간 오차 ≈ 설계 관계 − 결합 거리");
		assertTrue(close.error() < 1f, "이벤트 19 끌기로 설계 관계: " + close);
	}

	/**
	 * 몸 캡슐 (Sold +0x234) 이 설계 거리보다 큰 처형 (37 문서 §11.5): 사이클롭스 (캡슐 합 57.6 &gt; 설계 29.7), 하피 (19.6 &gt; 7.9, 뒤에서).
	 * <ul>
	 *   <li>원작 밀기 규칙 [0x1E2DD8 / 0x1E28B0 — GRAB stateMask 7 = 단단함] 으로는 GRAB 이 캡슐 경계에서 닿는다 (사이클롭스 결합 ≥ 45, 하피 ≥ 13).</li>
	 *   <li>그 어긋남은 이벤트 19 (FLAG_4) 창이 메운다 [확실: 0x206B44 → +0x1AC |= 0x4, 0x205B28 의 0x24 검사 → 0x204BF0]: 사이클롭스는 적 240
	 *       MAICYWMINI02 [0–0.86] 이 영웅을, 하피는 영웅 270 MAIHARWINGRIP [0–1] 이 하피를 끈다 → 0.5 초 뒤 오차 &lt; 1 (예전: 27.3 / 10.2).</li>
	 *   <li>밀기를 빼면 (비교용) GRAB 첫 접촉이 그대로 설계 관계.</li>
	 * </ul>
	 */
	@Test
	void capsuleLimitedFinishers() throws Exception {
		for (String w : new String[]{"R_CY2.WAD", "R_HAR.WAD"}) {
			Assumptions.assumeTrue(Files.exists(rom().resolve(w)));
			Supplier<GrabRelation.Trial> t = trials(w);
			GrabRelation.Result free = GrabRelation.search(t, 5, 130, false);
			assertNotNull(free, w);
			assertTrue(free.contact() < 1f, w + " 밀기 없으면 첫 접촉 = 설계 관계: " + free);
			// 서 있는 사이클롭스 (잡기 원 상태, 변위 0) 는 자기를 밀지 않는다 (0x1EBB38, 32 문서) → 영웅이 캡슐 안 깊이 (수평 < 45.6) 에서 시작하는 모의는
			// 원작에 생길 수 없다 (영웅은 걸어 들어오며 늘 자기를 밀어 경계 밖에 있음 — 플레이어는 정지 판정 없음). 하피 (날기 = 공중) 는 늘 자기를 민다
			GrabRelation.Result pushed = GrabRelation.search(t, w.equals("R_CY2.WAD") ? 50 : 5, 130, true);
			assertTrue(pushed.link() > free.link() + 5, w + " 원작 밀기로 첫 접촉은 설계 거리 밖: " + pushed + " / " + free);
			assertTrue(pushed.contact() > 5f, w + ": " + pushed);
			assertEquals(0f, pushed.error(), 1f, w + " 이벤트 19 끌기로 설계 관계 (±1): " + pushed);
			for (float d : new float[]{40, 60, 80}) {
				GrabRelation.Result r = GrabRelation.run(t.get(), d, true);
				assertEquals(0f, r.error(), 1f, w + " 시작 " + d + ": " + r);
			}
			GrabRelation.Trial probe = t.get();
			if (w.equals("R_CY2.WAD")) {
				// 캡슐 경계 (영웅 쪽 수평 45.6) 근처 — 밀기 후보는 질의 구에 닿는 사이클롭스 CDV 구가 있을 때만이라 (0x175648) 자세에 따라 조금 안쪽 (≈ 43)
				assertTrue(pushed.link() >= 40f, "사이클롭스 캡슐 경계 근처: " + pushed);
				CombatFile.Move m = probe.enemy().combat().data().move(240);
				assertNotNull(SyncPull.linkedPullWindow(m, 0.5f), "적 240 이벤트 19 [0–0.86]");
				assertNull(SyncPull.linkedPullWindow(m, 0.9f));
				assertNull(SyncPull.pullWindow(m, 0.5f), "이벤트 22 는 없음");
			} else {
				assertTrue(pushed.link() >= 13f, "하피가 자기를 민다 (하피 쪽 수평 17.3): " + pushed);
				assertNotNull(SyncPull.linkedPullWindow(probe.hero().data().move(270), 0.5f), "영웅 270 이벤트 19 [0–1]");
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
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01C.WAD")));
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "ATHN01C.WAD"), perm, 252, "hydra enc2");
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
