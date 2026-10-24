package devple.gow.engine.game;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이웃 슬롯 상자 (43 문서 §21): ATHN05C 항아리 방 (영상 8082–8110) 의 상자 둘은 ATHN05D 의 Creator (Chest3 CSM 010 고르곤의 눈 · Chest4 CSM 011
 * 불사조 깃털) 인데 방 바닥은 05C 에만 있어 영웅이 거기 서면 현재 레벨 = 05C, 05D = 이웃이다. 원작은 두 슬롯이 다 살아 있어 Creator 0x1ACCA8 이
 * 슬롯마다 GO 를 만들고 잡기 질의 0x1C3F18 은 슬롯을 가리지 않는다 — 예전 엔진은 이웃 상자를 만들지 않아 깃털 상자가 없었다 (영상 8100 깃털 카드).
 * 연 상자는 이음·레벨 기억으로 다시 만들 때 연 채다 (예전엔 닫힌 채 다시 나와 아이템을 또 줬다).
 */
class NeighbourChestTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static LevelData level(String name) throws Exception {
		Path p = Content.file(ROM, name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return new LevelData(WadFile.load(p));
	}

	private static Tweaks tweaks(LevelData lv) throws Exception {
		Tweaks t = new Tweaks();
		t.load(WadFile.load(Content.file(ROM, Region.of(ROM).perm())));
		t.load(lv.wad);
		return t;
	}

	/** Creator 생성 통지를 SceneNav 와 같은 길 ({@link SceneNav#addChest}) 로 상자 목록에 넣는 세계. */
	private static ScriptWorld world(LevelData lv, ScriptHost host, Interactables into, Tweaks tw, ScriptWorld.Memory mem) {
		ScriptWorld[] w = new ScriptWorld[1];
		w[0] = new ScriptWorld(lv, host, new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				SceneNav.addChest(into, w[0], source, tw);
			}
		});
		if (mem != null) {
			w[0].restore(mem);
		}
		w[0].start();
		return w[0];
	}

	@Test
	void jarRoomChestsAreAthn05dCreatorsOnAthn05cFloor() throws Exception {
		LevelData c = level("ATHN05C"), d = level("ATHN05D");
		Interactables items = new Interactables();
		world(d, ScriptHost.neighbour(new ScriptHost()), items, tweaks(d), null);
		Interactables.Item health = items.byOwner("gochest3"), magic = items.byOwner("gochest4");
		assertNotNull(health);
		assertNotNull(magic);
		assertEquals(10, health.template);
		assertEquals(11, magic.template);
		assertEquals("MagicShard", magic.label, "05D TWK CSM 011");
		assertEquals(11, magic.chestItem, "아이템 11 = 불사조 깃털 (#cb865a1c = −65525)");
		assertEquals(10, health.chestItem);
		assertEquals(-1552f, magic.pos.x, 1f);
		assertEquals(2070.2f, magic.pos.z, 1f);
		// 05C TWK 에는 CSM 011 이 없다 → 이웃 상자는 이웃 레벨 TWK 로 읽어야 한다
		assertNull(tweaks(c).s("/TweakTemplates/CSM/011/", "Instance Name", null));
		// 상자 앞 (손잡이 쪽 +z) 바닥은 05C 에만 → 걷기 이음 규칙으로 현재 레벨 = 05C, 상자는 이웃 슬롯 것
		LevelCollision cc = new LevelCollision(c.sheets), dc = new LevelCollision(d.sheets);
		for (Interactables.Item it : new Interactables.Item[]{health, magic}) {
			assertEquals(864f, cc.groundHeight(it.pos.x, 900, it.pos.z + 50, 200), 1f);
			assertTrue(Float.isNaN(dc.groundHeight(it.pos.x, 900, it.pos.z + 50, 200)), "05D 바닥 없음");
		}
	}

	@Test
	void openedChestStaysOpenWhenTheWorldIsMadeAgain() throws Exception {
		LevelData d = level("ATHN05D");
		Tweaks tw = tweaks(d);
		ScriptHost host = new ScriptHost();
		Interactables first = new Interactables();
		ScriptWorld w = world(d, host, first, tw, null);
		assertFalse(first.byOwner("gochest4").done);
		w.markOpened("gochest4"); // SceneNav.interactDone (상자 과제 완료)
		assertTrue(w.opened("GOCHEST4"), "키는 대소문자 무시");
		// 이음으로 넘겨 쓴 세계를 새 씬에 붙임 (ScriptWorld.recreate → Creator 다시)
		Interactables again = new Interactables();
		ScriptWorld[] kept = {w};
		w.rebind(new ScriptListener() {
			@Override
			public void create(ScriptEntity source) {
				SceneNav.addChest(again, kept[0], source, tw);
			}
		});
		w.recreate();
		assertTrue(again.byOwner("gochest4").done, "연 상자는 연 채 (잡히지 않는다)");
		assertFalse(again.byOwner("gochest3").done);
		// 레벨 기억 (슬롯에서 내려갔다 다시 읽음)
		ScriptWorld.Memory m = w.memory();
		assertTrue(m.opened().contains("gochest4"));
		Interactables reloaded = new Interactables();
		world(d, new ScriptHost(), reloaded, tw, m);
		assertTrue(reloaded.byOwner("gochest4").done);
		assertFalse(reloaded.byOwner("gochest3").done);
	}
}
