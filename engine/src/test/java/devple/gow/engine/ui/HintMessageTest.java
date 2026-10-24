package devple.gow.engine.ui;

import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.MessageTable;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 제우스의 분노 첫 안내 509 (영상 8056, 43 §25): 초 0 메시지는 전역 정지를 걸지 않고 (0x1B9F60), 첫 안내는 초 0 으로 요청한다 (0x1DBEB0 → 0x21D980) —
 * PS2 는 안내 상자 동안 HUD 미터가 떠 있고 게임이 돈다. 인라인 버튼 그림 L2Button 의 배지 글자 "L2" 는 아이콘 클립 안 동적 라벨 _level0/:StaticTextL2 이고
 * 값은 FLP_HUDK 루트 프레임 0 액션이 넣는다.
 */
class HintMessageTest {

	private static Elf exe() throws Exception {
		Path p = FlpPlayerTest.rom().toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	private static void has(Elf e, int pc, String mnem, String ops) {
		R5900.Insn i = at(e, pc);
		assertEquals(mnem, i.mnem(), String.format("%08x %s", pc, i.text()));
		assertTrue(i.ops().replace(" ", "").contains(ops.replace(" ", "")), String.format("%08x %s (기대 %s)", pc, i.text(), ops));
	}

	private static void call(Elf e, int pc, int target) {
		R5900.Insn i = at(e, pc);
		assertEquals(target, i.target(), String.format("%08x %s", pc, i.text()));
	}

	/** 0x1B9F60(gm, a1): +0x153C (초) == 0 이면 0x1BF080(슬롯 1, 0) · 0x21D970(a1), 아니면 0x1BF080(슬롯 1, a1) · 0x21D970(0). */
	@Test
	void zeroSecondMessageDoesNotSetGlobalIdle() throws Exception {
		Elf e = exe();
		has(e, 0x1b9f70, "lwc1", "$f1, 0x153c(a0)");
		assertEquals("c.eq.s", at(e, 0x1b9f74).mnem());
		R5900.Insn br = at(e, 0x1b9f7c);
		assertEquals("bc1f", br.mnem(), br.text());
		assertEquals(0x1b9fa0, br.target());
		// 같음 (초 0): 슬롯 1 에 0
		has(e, 0x1b9f84, "li", "a0, 0x1");
		call(e, 0x1b9f88, 0x1bf080);
		has(e, 0x1b9f8c, "move", "a1, zero");
		call(e, 0x1b9f90, 0x21d970);
		// 다름 (음수): 슬롯 1 에 a1 (= 1, 쪽 표시 0x1B96D0 이 부름)
		has(e, 0x1b9fa0, "li", "a0, 0x1");
		call(e, 0x1b9fa4, 0x1bf080);
		has(e, 0x1b9fa8, "move", "a1, s0");
		// 쪽 표시: 초 > 0 이 아니면 0x1B9F60(gm, 1)
		call(e, 0x1b9aac, 0x1b9f60);
		has(e, 0x1b9ab0, "li", "a1, 0x1");
		// 첫 안내 0x1DBEB0: 메시지 500 + id 를 $f12 = 0 (초 0) 으로 0x21D980 요청
		has(e, 0x1dbf50, "addiu", "s0, a1, 0x1f4");
		has(e, 0x1dbf70, "mtc1", "zero, $f12");
		call(e, 0x1dbf78, 0x21d980);
		// 질문 메뉴 0x1B9BA0 은 초 −1 (0xBF80 << 16) 로 정지
		has(e, 0x1b9f20, "lui", "at, 0xbf80");
		call(e, 0x1b9f38, 0x1b9f60);
	}

	/** 메시지 쪽 표시 0x1B96D0 은 어느 갈래든 HUD 리셋 0x1BA150(hud, 1) (미터 내림) 을 부른다. */
	@Test
	void messagePageResetsTheHudMeter() throws Exception {
		Elf e = exe();
		for (int pc : new int[]{0x1b9974, 0x1b9994, 0x1b9a18, 0x1b9a74}) {
			call(e, pc, 0x1ba150);
			has(e, pc + 4, "li", "a1, 0x1");
		}
		// 0x1BA150(hud, 1): +0x157C (미터 올라옴) 이면 MeterBar_Event (+0x210) = 0
		has(e, 0x1ba170, "lw", "v0, 0x157c(s0)");
		has(e, 0x1ba17c, "sw", "zero, 0x210(s0)");
	}

	/** L2Button 클립 (배지 메시 + 'L2' 클립) 의 글자는 동적 라벨 _level0/:StaticTextL2 — 루트 프레임 0 이 "L2" 를 넣는다. */
	@Test
	void l2BadgeLetterIsADynamicLabelSetByTheHudRoot() throws Exception {
		Path dir = FlpPlayerTest.rom();
		Assumptions.assumeTrue(Files.exists(dir.resolve("R_PERMK.WAD")), "gow-rom 없음");
		WadFile w = WadFile.load(dir.resolve("R_PERMK.WAD"));
		WadTag t = w.tags().stream().filter(x -> x.name().equals("FLP_HUDK") && x.hasData()).findFirst().orElseThrow();
		FlpFile flp = new FlpFile(t.data());
		FlpFile.Timeline badge = clipNamed(flp, "L2Button");
		assertNotNull(badge, "L2Button 클립");
		FlpFile.Timeline letters = null;
		for (FlpFile.Layer l : badge.layers()) {
			for (FlpFile.KeyFrame k : l.keys()) {
				if ("L2".equals(k.name()) && flp.handlers.get(k.handler()).type() == 7) {
					letters = flp.clips.get(flp.handlers.get(k.handler()).index());
				}
			}
		}
		assertNotNull(letters, "배지 안 'L2' 클립");
		String var = null;
		for (FlpFile.Layer l : letters.layers()) {
			for (FlpFile.KeyFrame k : l.keys()) {
				FlpFile.Handler h = flp.handlers.get(k.handler());
				if (h.type() == 5) {
					var = flp.dynamicLabels.get(h.index()).variable();
				}
			}
		}
		assertEquals("_level0/:StaticTextL2", var);
		FlpPlayer p = new FlpPlayer(flp, new UiVariables(), MessageTable.fromWad(w).orElseThrow());
		for (int i = 0; i < 30; i++) {
			p.tick();
		}
		assertEquals("L2", p.getVariable("_level0/:StaticTextL2"));
		assertEquals("R2", p.getVariable("_level0/:StaticTextR2"));
		assertEquals("L1", p.getVariable("_level0/:StaticTextL1"));
	}

	private static FlpFile.Timeline clipNamed(FlpFile flp, String name) {
		java.util.List<FlpFile.Timeline> all = new java.util.ArrayList<>(flp.clips);
		all.add(flp.root);
		for (FlpFile.Timeline tl : all) {
			for (FlpFile.Layer l : tl.layers()) {
				for (FlpFile.KeyFrame k : l.keys()) {
					if (name.equals(k.name()) && k.handler() < flp.handlers.size() && flp.handlers.get(k.handler()).type() == 7) {
						return flp.clips.get(flp.handlers.get(k.handler()).index());
					}
				}
			}
		}
		return null;
	}
}
