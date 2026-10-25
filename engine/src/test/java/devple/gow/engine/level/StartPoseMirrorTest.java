package devple.gow.engine.level;

import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.PlacedMesh;
import devple.gow.tools.level.StartPose;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 시각 QA 2 (43 문서 §6): 배치 오브젝트 시작 act 자세 (GO 리셋 0x1309C0), SCR_Mirror 반사 평면. */
class StartPoseMirrorTest {

	private static WadFile wad(String name) throws Exception {
		Path p = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static LevelLayout.Placement placement(LevelData d, String name) {
		return d.layout.placements().stream().filter(p -> p.instance().name().equalsIgnoreCase(name)).findFirst().orElseThrow();
	}

	private static StartPose start(WadFile w, LevelData d, String inst) {
		LevelLayout.Placement p = placement(d, inst);
		return StartPose.of(p, AnimationSet.load(w, p.object().refs("ANM_").getFirst()));
	}

	@Test
	void instanceStartActAndTime() throws Exception {
		// ATHN08A 나무 흔들림: 인스턴스마다 act 1–3 · 시작 시간 0–13 초 (0x50 f32, 0x54 u16)
		WadFile w = wad("ATHN08A");
		LevelData d = new LevelData(w);
		LevelLayout.Placement p = placement(d, "gofellergroup20");
		assertEquals(3, p.instance().startAct());
		assertEquals(13f, p.instance().startTime(), 1e-6f);
		StartPose sp = start(w, d, "gofellergroup20");
		assertEquals("anim03", sp.act().name);
		assertTrue(sp.act().looping(), "반복 act → 계속 재생");
		// 0x56 u16 = 같은 템플릿 안 번호 (act 아님): ATHN03A gobreakbucket016 → act 0
		LevelData a3 = new LevelData(wad("ATHN03A"));
		assertEquals(0, placement(a3, "gobreakbucket016").instance().startAct());
	}

	@Test
	void dmcBarrierHiddenAtStart() throws Exception {
		// PAND03A DMC 결계: act 0 "DMC" 의 byte 트랙이 dmcSquare(1)·dogHead(3) = 0 → 자손 포함 숨김 (이전: 바인드 자세 붉은 쐐기)
		WadFile w = wad("PAND03A");
		LevelData d = new LevelData(w);
		StartPose sp = start(w, d, "godmcdog2");
		assertEquals("DMC", sp.act().name);
		assertFalse(sp.act().looping());
		assertNotNull(sp.hidden());
		assertTrue(sp.hidden()[1] && sp.hidden()[2] && sp.hidden()[3] && sp.hidden()[19]);
		assertFalse(sp.hidden()[0]);
		LevelLayout.Placement p = placement(d, "godmcdog2");
		GowModel gm = GowModel.load(w).stream().filter(m -> m.name.equals(p.model())).findFirst().orElseThrow();
		TriMesh bind = PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0);
		TriMesh atStart = PlacedMesh.build(gm, p.object().skeleton(), sp.jointWorld(), 0, sp.hidden());
		assertFalse(bind.subs.isEmpty());
		assertTrue(atStart.subs.isEmpty(), "시작 자세에서는 결계 전체가 숨김");
		// 스크립트 dogIn 은 1 → 보임
		var in = AnimationSet.load(w, "ANM_dmcdog2").find("dogIn");
		assertNull(in.hiddenJoints(p.object().skeleton(), 1f));
	}

	@Test
	void ambientRocksLoop() throws Exception {
		// PAND03C 굴러가는 바위: 반복 act 30 초, 시작 시간 엇갈림 (2.5 … 27.75)
		WadFile w = wad("PAND03C");
		LevelData d = new LevelData(w);
		StartPose sp = start(w, d, "goleftrock2");
		assertTrue(sp.act().looping());
		assertEquals(9.25f, sp.time(), 1e-4f);
		assertTrue(sp.differsFromBind(placement(d, "goleftrock2").jointWorld(), 1f));
	}

	@Test
	void scriptAnimatedAndFlagZeroGosHoldTheirStartPose() throws Exception {
		// 43 §32: GO +0xF8 = 인스턴스 플래그 (0x1307C8), 모드 1 Animator 등록이 2 로 덮음 (0x1AAFE8) → 0x130F48 이 애니를 돌리지 않음.
		// DEST03 모래벽 gosandwedge: 반복 act sandWedge (12.33 초) 지만 sandWedge-Animator1 (뿔피리 100 % + 3 초) 전에는 닫힌 자세 (영상 2716–2724)
		WadFile w = wad("DEST03");
		LevelData d = new LevelData(w);
		LevelLayout.Placement wedge = placement(d, "gosandwedge");
		assertEquals(1, wedge.instance().flags() & 1);
		assertTrue(start(w, d, "gosandwedge").act().looping());
		assertTrue(d.animatorOwners().contains("gosandwedge"));
		assertFalse(d.selfAnimates(wedge), "모드 1 Animator 가 붙은 GO 는 스스로 돌지 않는다");
		assertFalse(d.selfAnimates(placement(d, "gondgrpfakemovingwall")));
		// 시작 자세: 갈라지는 모래 덩어리 (관절 3 sandPartingChunk95) 가 보이고 쐐기는 뿔피리 앞 (z 6019.5)
		StartPose sp = start(w, d, "gosandwedge");
		assertTrue(sp.hidden() == null || !sp.hidden()[3]);
		assertEquals(6019.5f, sp.jointWorld()[0][14], 0.5f);
		// 플래그 0 (비트 0 꺼짐) 인 반복 act GO: PAND00B goelevatorgrpa — 예전 엔진은 26.7 초마다 −8386 아래로 감았다
		LevelData b = new LevelData(wad("PAND00B"));
		LevelLayout.Placement elev = placement(b, "goelevatorgrpa");
		assertEquals(0, elev.instance().flags());
		assertFalse(b.selfAnimates(elev));
		// 스크립트 없는 플래그 1 반복 act 는 그대로 돈다: DEST02 크러셔 톱니 gogearanim (Vis 엔티티만, Animator 없음)
		LevelData d2 = new LevelData(wad("DEST02"));
		assertTrue(d2.selfAnimates(placement(d2, "gogearanim")));
		assertFalse(d2.selfAnimates(placement(d2, "goconveyer")), "컨베이어는 CreationSensor → Conveyer-AnimCycle 로만 돈다");
		// ATHN08A 나무 흔들림 (스크립트 없음) 도 그대로
		LevelData a8 = new LevelData(wad("ATHN08A"));
		assertTrue(a8.selfAnimates(placement(a8, "gofellergroup20")));
	}

	@Test
	void mirrorPlanes() throws Exception {
		// OLYMP02 gomirror = 대리석 바닥 (MAT_tnMarblePlainNew00b, 알파 블렌드) — 수평 평면
		WadFile w = wad("OLYMP02");
		Map<String, int[]> owners = Mirrors.owners(new EnvParams(w));
		assertTrue(owners.containsKey("gomirror"));
		assertArrayEquals(new int[]{0, 0}, owners.get("gomirror"));
		LevelData d = new LevelData(w);
		LevelLayout.Placement p = placement(d, "gomirror");
		GowModel gm = GowModel.load(w).stream().filter(m -> m.name.equals(p.model())).findFirst().orElseThrow();
		Mirrors.Mirror m = Mirrors.fit("gomirror", PlacedMesh.build(gm, p.object().skeleton(), p.jointWorld(), 0), owners.get("gomirror"));
		assertNotNull(m);
		assertEquals(1f, Math.abs(m.normal().y), 1e-3f);
		assertTrue(m.maxDev() < 2f, "평면 이탈 " + m.maxDev()); // 가장자리 조각 1.04 — 엔진 허용 = max(0.5, 크기 1%)
		// 반사: 평면 위 점은 그대로, 평면에서 h 위 점은 h 아래로
		org.joml.Vector3f q = new org.joml.Vector3f(0, m.d() / m.normal().y + 10, 0);
		assertEquals(m.d() / m.normal().y - 10, m.reflect(q).y, 1e-3f);
		// ATHN03A: 템플릿 값 (0, 1) 의 거울 3 개
		Map<String, int[]> a3 = Mirrors.owners(new EnvParams(wad("ATHN03A")));
		assertArrayEquals(new int[]{0, 1}, a3.get("gogroup2839"));
		assertEquals(3, a3.size());
	}
}
