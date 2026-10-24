package devple.gow.engine.game;

import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.level.CameraRig;
import devple.gow.engine.nav.HeroNavigator;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.engine.ui.FlpPlayer;
import devple.gow.engine.ui.UiPad;
import devple.gow.engine.ui.UiVariables;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 광장 ATHN04A · 대리석 실내 ATHN04B · 해치 아래 ATHN04C (PS2 영상 5846–6461, 43 문서 §18): 저장 메뉴 닫기, 열쇠 전 해치, 넘겨 쓴 세계의 Vis·Creator,
 * 이웃 슬롯 창살 (TAndF 물리 들어 올림), 투신 크레인 카메라, 상자 아이템, 첫 안내.
 */
class AthensInteriorTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static Path rom(String name) {
		Path p = Content.file(ROM, name);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return p;
	}

	private static WadFile perm() throws Exception {
		return WadFile.load(rom(Region.of(ROM).perm()));
	}

	private static LevelData level(String name) throws Exception {
		return new LevelData(WadFile.load(rom(name + ".WAD")));
	}

	private static Tweaks tweaks(LevelData lv) throws Exception {
		Tweaks t = new Tweaks();
		t.load(perm());
		t.load(lv.wad);
		return t;
	}

	// ---- HUD 저장 메뉴 (FLP_HUDK SaveGameMenu) ----

	private static FlpPlayer hud(UiVariables v) throws Exception {
		WadFile w = WadFile.load(rom("R_PERMK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		return new FlpPlayer(new FlpFile(t.data()), v, MessageTable.fromWad(w).orElseThrow());
	}

	private static void idle(FlpPlayer p, int n) {
		for (int i = 0; i < n; i++) {
			p.pad(0);
			p.tick();
		}
	}

	private static void press(FlpPlayer p, int mask) {
		idle(p, 45);
		p.pad(mask);
		p.tick();
		idle(p, 5);
	}

	/**
	 * ○ 로 답하면 FLP 는 SaveGameMenu 를 "Off" (질문만 숨김) 로 두고 Pause_Check 10 → 0 — Ω 배경은 남는다. 원작 상태 18 0x220740 이 10 프레임 뒤
	 * HUD SaveGame_Event(+0x268) = 0 을 써야 Animate_Off 로 사라진다 (엔진은 이것을 보내지 않아 저장 뒤 Ω 가 화면에 남았다). 다음 질문의 PS2_Done 1 은
	 * 0x21D8B0 처럼 Done = 0 으로 되돌려 둬야 "바뀜" 으로 들어온다 (두 번째 세이브 포인트에서 질문이 닫히지 않던 문제).
	 */
	@Test
	void saveMenuNeedsEventZeroToClose() throws Exception {
		UiVariables v = new UiVariables();
		v.set("PS2_EnableButtons", 1);
		List<String> log = new ArrayList<>();
		v.addListener((n, val) -> log.add(n + "=" + val));
		FlpPlayer p = hud(v);
		idle(p, 30);
		v.event("PS2_SaveGame_Event", 1);
		idle(p, 30);
		int open = p.frameOf("SaveGameMenu");
		press(p, UiPad.CIRCLE);
		assertTrue(log.contains("PS2_Done=1"), log.toString());
		idle(p, 30);
		int answered = p.frameOf("SaveGameMenu");
		assertTrue(answered >= 6 && answered <= 9, "Off (질문만 숨김, Ω 그대로): " + open + " → " + answered);
		idle(p, 60);
		assertEquals(answered, p.frameOf("SaveGameMenu"), "이벤트 0 이 없으면 그대로 (Ω 남음)");
		v.event("PS2_SaveGame_Event", 0);
		idle(p, 2);
		int closing = p.frameOf("SaveGameMenu");
		assertTrue(closing >= 10 && closing <= 13, "Animate_Off: " + closing);
		idle(p, 30);
		assertEquals(0, p.frameOf("SaveGameMenu"), "닫힘");

		// 두 번째 질문: Done 을 되돌리지 않으면 ○ 의 1 이 변화가 아니다
		log.clear();
		v.event("PS2_SaveGame_Event", 1);
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertFalse(log.contains("PS2_Done=1"), "Done 1 그대로 → 알림 없음: " + log);
		v.event("PS2_SaveGame_Event", 0);
		idle(p, 30);
		log.clear();
		v.set("PS2_Done", 0); // 0x21D8B0: Menu_Choice·Done = 0
		v.event("PS2_SaveGame_Event", 1);
		idle(p, 30);
		press(p, UiPad.CIRCLE);
		assertTrue(log.contains("PS2_Done=1"), "되돌려 두면 알림: " + log);
	}

	/** 메모리카드 예/아니오 화면 (불러올까? 10 · 덮어쓸까? 15 · 새로 만들까? 16) — FLP 기본 선택 아니오 (검증 봇은 ↑ 뒤 ○). */
	@Test
	void memoryCardYesNoScreens() throws Exception {
		Path dir = Files.createTempDirectory("gowcard");
		MemoryCardFlow f = MemoryCardFlow.save(new UiVariables(), null, dir, SaveGame::new);
		f.start();
		assertFalse(f.yesNoScreen(), "카드 선택");
		f.select(1);
		assertFalse(f.yesNoScreen(), "파일 선택");
		f.select(1);
		assertEquals(16, f.state());
		assertTrue(f.yesNoScreen(), "새로 만들까?");
	}

	// ---- 해치 trapDoor2 / 창살 LiftDoor13 ----

	/** 생성 스크립트 trapDoor2-main-CDsOff (Vis Attr11_0 = 2) — 충돌이 꺼진 해치는 R2 로 잡히지 않는다 (잡기 질의 0x1C3F18 = 충돌 세계). */
	@Test
	void hatchIsNotGrabbableUntilTheKeyTurnsCollisionOn() throws Exception {
		LevelData lv = level("ATHN04A");
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		w.start();
		int[] s = w.visState("gotrapdoor2");
		assertNotNull(s, "trapDoor2-main-CDsOff");
		assertEquals(2, s[3], "Attr11_0 끔");
		Interactables items = Interactables.of(lv, tweaks(lv));
		Interactables.Item hatch = items.byOwner("gotrapdoor2");
		assertEquals(Interactables.Kind.LIFT, hatch.kind);
		Vector3f feet = new Vector3f(1191, 304, 1247), fwd = new Vector3f(1, 0, 0); // 손잡이 (1210, 329, 1248) 를 보고 선 자리 (영상 6440)
		assertNotNull(items.grabbable(feet, fwd, HeroNavigator.USE_RADIUS), "충돌 켜짐이면 잡힘");
		hatch.noCollision = true;
		assertNull(items.grabbable(feet, fwd, HeroNavigator.USE_RADIUS), "CDsOff → 안 잡힘");
		hatch.noCollision = false; // MenuYes → CDsOn (Delay 2)
		assertNotNull(items.grabbable(feet, fwd, HeroNavigator.USE_RADIUS));
	}

	/** 넘겨 쓴 세계를 새 씬에 붙이면 (recreate) Creator 와 함께 Vis 가 남긴 상태도 새 리스너로 다시 간다 — FadeTime 은 0 (바로). */
	@Test
	void recreateReplaysVisAndCreators() throws Exception {
		LevelData lv = level("ATHN04A");
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
		});
		w.start();
		List<String> creators = new ArrayList<>();
		List<int[]> hatch = new ArrayList<>();
		w.rebind(new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				creators.add(String.valueOf(w.slot(source, "Name0")));
			}

			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				if (objects.contains("gotrapdoor2")) {
					hatch.add(values);
				}
			}
		});
		w.recreate();
		assertEquals(2, creators.stream().filter("savepoint"::equals).count(), "SaveGameZone · SaveGameZone3 (SaveGameZone2 는 슬롯 8 거짓): " + creators);
		assertTrue(creators.contains("chest"), creators.toString());
		assertEquals(1, hatch.size());
		assertEquals(2, hatch.getFirst()[3], "해치 충돌 끔 다시");
		assertEquals(0, hatch.getFirst()[8], "FadeTime 없이");
	}

	/**
	 * ATHN04A 창살 LiftDoor13 (TAndF 400 "Athens3ALiftGat"): 문 ANM 에 템플릿 act (liftdoor2/liftdoor3) 가 없고 "LiftDoor13" (길이 0) 하나 →
	 * 원작은 문 Y 를 직접 움직인다 (잡은 동안 영웅 관절, 끝 = 처음 Y + #8743c048 5.0 × 16, 0x214058 / 0x214180). 들어 올리면 충돌도 따라 올라
	 * 지나갈 수 있고, 그 높이는 이음 상태로 넘어가 다음 씬에서도 끝난 문이다. 해치 (trapOpen 0.6 초 act 있음) 는 애니로 연다.
	 */
	@Test
	void liftGateRisesByTheTemplateHeight() throws Exception {
		LevelData lv = level("ATHN04A");
		Interactables items = Interactables.of(lv, tweaks(lv));
		Interactables.Item gate = items.byOwner("goliftdoor13");
		assertEquals(80f, gate.liftHeight, 1e-3f, "5.0 × 16");
		Platforms ps = Platforms.of(lv, Set.of(), SceneNav.posedOwners(items));
		assertTrue(SceneNav.physicalLift(gate, ps), "act 없음 → 높이로");
		assertFalse(SceneNav.physicalLift(items.byOwner("gotrapdoor2"), ps), "해치는 trapOpen act");
		Vector3f c = new Vector3f(1232, -60, 1222); // 창살 바로 북쪽 (영웅 몸 구 중심 = 발 + 20)
		assertTrue(ps.collideSphere(c, 12, Set.of(), NavWorld.HERO_BODY_EXCLUDE), "닫힌 창살이 막음");
		assertTrue(ps.lift("goliftdoor13", gate.liftHeight));
		c.set(1232, -60, 1222);
		assertFalse(ps.collideSphere(c, 12, Set.of(), NavWorld.HERO_BODY_EXCLUDE), "들어 올린 창살 밑으로");
		Platforms.State st = ps.states().stream().filter(x -> x.owner().equalsIgnoreCase("goliftdoor13")).findFirst().orElseThrow();
		assertEquals(80f, st.lift(), 1e-3f);
		Platforms again = Platforms.of(lv, Set.of(), SceneNav.posedOwners(items));
		again.restore(List.of(st));
		c.set(1232, -60, 1222);
		assertFalse(again.collideSphere(c, 12, Set.of(), NavWorld.HERO_BODY_EXCLUDE), "이음 뒤에도 열림");
		Interactables fresh = Interactables.of(lv, tweaks(lv));
		SceneNav.restoreItems(fresh, List.of(st));
		assertTrue(fresh.byOwner("goliftdoor13").done, "다시 잡히지 않음");
	}

	// ---- 투신 크레인 카메라 ----

	/**
	 * SuicideCam (플래그 0x14FE: 0x100·0x40000 없음, JumpPan 11 초): 원작 0x1F3768 은 카메라 노드 월드 행렬로 각을 정한다 — 4 초쯤 떨어진 여자 몸
	 * 높이까지 내려가 내려다보고 영웅 (난간) 은 등 뒤 (영상 5929–5935). 예전 엔진은 영웅을 봤다.
	 */
	@Test
	void suicideCamLooksDownAtTheFallenBody() throws Exception {
		LevelData lv = level("ATHN04A");
		CameraRig rig = new CameraRig(lv);
		Vector3f hero = new Vector3f(1440, 304, 1030);
		rig.update(0, hero, 0, false);
		rig.setVolumeQuery(false);
		rig.request("SuicideCam");
		for (int i = 0; i < 4 * 60; i++) {
			rig.update(1 / 60f, hero, 0, false);
		}
		assertEquals("SuicideCam", rig.current());
		Vector3f e = rig.eye(), f = rig.pose().forward(new Vector3f());
		assertTrue(e.y < 60, "몸 높이까지 내려감: " + e);
		assertTrue(f.y < -0.8f, "내려다봄: " + f);
		assertTrue(f.dot(new Vector3f(hero).sub(e).normalize()) < -0.5f, "영웅은 뒤: " + f);
		for (int i = 0; i < 7 * 60; i++) {
			rig.update(1 / 60f, hero, 0, false);
		}
		assertTrue(rig.eye().y > 380, "끝에 난간 위로 올라옴: " + rig.eye());
	}

	// ---- 상자 아이템 · 첫 안내 ----

	/** 상자 Creator 슬롯 5 = CSM 템플릿: 11 → 불사조 깃털 (아이템 11, 열린 뒤 0.1 초), 10 → 고르곤의 눈 (10) — ATHN04A Chest5 / Chest6. */
	@Test
	void chestItemsComeFromTheCsmTemplate() throws Exception {
		LevelData lv = level("ATHN04A");
		Tweaks tw = tweaks(lv);
		Interactables items = new Interactables();
		Interactables.Item feather = items.addChest("gochest5", new Vector3f(), 11, 0, tw);
		assertEquals(11, feather.chestItem);
		assertEquals(0.1f, feather.chestItemDelay, 1e-4f);
		assertEquals(10, items.addChest("gochest6", new Vector3f(), 10, 0, tw).chestItem);
		assertEquals(0, items.addChest("gochest8", new Vector3f(), 60, 0, tw).chestItem, "붉은 오브 상자");
	}

	/**
	 * FirstTimeHint 0x1DBEB0: 플레이 2 초 전·이미 본 안내는 안 됨, 안내 1 은 3 번째, 0x13 은 8 번째부터. 방패 해골 막기 (R_SKB M70) 의
	 * SCR_FirstTimeMsg idx 19 → 8 번째 막기에 메시지 519 (영상 6026).
	 */
	@Test
	void firstTimeHints() throws Exception {
		PlayerState p = new PlayerState(GameRules.load(perm()));
		assertFalse(p.firstTimeHint(7, 1.9f), "2 초 전");
		assertTrue(p.firstTimeHint(7, 3f));
		assertFalse(p.firstTimeHint(7, 3f), "한 번만");
		for (int i = 1; i <= 7; i++) {
			assertFalse(p.firstTimeHint(19, 10f), "막기 " + i);
		}
		assertTrue(p.firstTimeHint(19, 10f), "8 번째");
		assertFalse(p.firstTimeHint(1, 10f));
		assertFalse(p.firstTimeHint(1, 10f));
		assertTrue(p.firstTimeHint(1, 10f), "3 번째");
		EnemyFactory.Kind skb = EnemyFactory.load(rom("R_SKB.WAD"), perm(), -1);
		var block = skb.spawn().combat().data().move(70);
		assertNotNull(block, "SKBBLOCK");
		assertTrue(block.events().stream().anyMatch(ev -> ev.type() == 8 && ev.hash() == GameScene.SCR_FIRST_TIME_MSG && ev.index() == 19),
				"SCR_FirstTimeMsg 19");
	}
}
