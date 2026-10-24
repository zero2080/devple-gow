package devple.gow.engine.script;

import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Weapon;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Internal 플레이어 변수 (GameMgr getter 0x1AFE18 / setter 0x1AFFB0, 점프 표 0x2B5C10 / 0x2B5C80): 33 HP, 35 MP (0..최대), 36 최대 MP,
 * 37 · 38..42 아이템 9 · 3..7 보유 (쓰기 참 = AddItem). ATHN02D MedusaRipZone-EventCombat1 이 "set_bool Internal:39 true" 로 메두사의 시선을 준다.
 */
class GameScriptHostTest {

	static PlayerState player() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return new PlayerState(GameRules.load(WadFile.load(rom.resolve("R_PERMK.WAD"))));
	}

	@Test
	void internalItemVariablesGrantMagic() throws Exception {
		PlayerState p = player();
		ScriptHost h = new ScriptHost();
		h.players = () -> p;
		assertEquals(false, h.getVar(null, 1, 39, 2), "시선 없음");
		h.setVar(null, 1, 39, 2, true); // MedusaRipZone-EventCombat1
		assertTrue(p.owned(Weapon.MEDUSA));
		assertEquals(Weapon.MEDUSA, p.selectedMagic, "첫 마법 → 선택");
		assertEquals(true, h.getVar(null, 1, 39, 2));
		assertEquals(false, h.getVar(null, 1, 38, 2), "포세이돈 (아이템 3) 은 그대로");
		// set_float Internal:35 ← get_float Internal:36 = MP 가득, 범위 밖은 자름
		p.mp = 0;
		h.setVar(null, 1, 35, 0, h.getVar(null, 1, 36, 0));
		assertEquals(p.maxMp(), p.mp, 1e-3);
		h.setVar(null, 1, 35, 0, 1e6f);
		assertEquals(p.maxMp(), p.mp, 1e-3);
		h.setVar(null, 1, 33, 0, -5f);
		assertEquals(0f, p.hp, 1e-3, "HP 0..최대");
		// 이웃 레벨 호스트도 같은 플레이어
		ScriptHost n = ScriptHost.neighbour(h);
		assertEquals(true, n.getVar(null, 1, 39, 2));
	}

	/**
	 * Internal 0x12 (GameMgr_OnMessage 0x1B0B34: float → 0x3044A4) = 마법 재생 MP/초. 영웅 갱신 0x1D6F20 이 매 프레임 MP += 값/60 을
	 * 0..최대 MP 로 자른다. 예전 엔진은 0x12 를 버렸다 (ATHN02D RewardPlayer 10 / TurnFaceOffFinal 0).
	 */
	@Test
	void internal12SetsMagicRegeneration() throws Exception {
		PlayerState p = player();
		p.addItem(4, 1);
		ScriptHost h = new ScriptHost();
		h.players = () -> p;
		java.util.ArrayDeque<Object> st = new java.util.ArrayDeque<>();
		st.push(10f);
		h.call(null, 1, GameScriptHost.MAGIC_REGEN, 1, st);
		assertTrue(st.isEmpty(), "인자 1 개를 꺼냄");
		assertEquals(10f, p.mpRegen, 1e-6);
		p.mp = 0;
		p.regenMagic(0.5f);
		assertEquals(5f, p.mp, 1e-4);
		p.regenMagic(1000f);
		assertEquals(p.maxMp(), p.mp, 1e-4, "최대 MP 로 자름");
		st.push(0f);
		h.call(null, 1, GameScriptHost.MAGIC_REGEN, 1, st);
		p.mp = 3;
		p.regenMagic(1f);
		assertEquals(3f, p.mp, 1e-6, "0 = 재생 없음");
	}

	@Test
	void withoutPlayerInternalWritesAreIgnored() {
		ScriptHost h = new ScriptHost();
		h.setVar(null, 1, 39, 2, true);
		assertEquals(false, h.getVar(null, 1, 39, 2));
		assertEquals(0f, h.getVar(null, 1, 36, 0));
	}
}
