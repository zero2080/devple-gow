package devple.gow.tools.progress;

import devple.gow.tools.wad.WadFile;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 성장/저장/씬 흐름 도구.
 * <pre>
 *   progress:dump   &lt;gow-rom&gt;                업그레이드 비용표(FLP_HUDK), 성장 TWK, 오브, 아이템 ID, 세이브 구조
 *   progress:levels &lt;gow-rom&gt; [out.txt]      레벨 ESC 의 Load/LoadWad/LoadCheck/Warp/CheckPoint 호출과 WAD 연결
 *   progress:save   &lt;dataN.bin&gt;              세이브 파일 체크섬 검증 + PlayerProgress 해석
 *   progress:emitters &lt;level.WAD&gt;          레벨의 OrbEmitter(상자 등) 템플릿
 * </pre>
 */
public final class Cli {

	public static void main(String[] a) throws Exception {
		switch (a[0]) {
			case "dump" -> dump(Path.of(a[1]), System.out);
			case "levels" -> {
				if (a.length > 2) {
					try (PrintStream o = new PrintStream(Files.newOutputStream(Path.of(a[2])), true, "UTF-8")) {
						levels(Path.of(a[1]), o);
					}
				} else {
					levels(Path.of(a[1]), System.out);
				}
			}
			case "save" -> save(Path.of(a[1]), System.out);
			case "emitters" -> emitters(TweakIndex.of(WadFile.load(Path.of(a[1]))), System.out);
			default -> throw new IllegalArgumentException(a[0]);
		}
	}

	static void dump(Path rom, PrintStream o) throws Exception {
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));

		o.println("== 업그레이드 비용 (R_PERMK/FLP_HUDK 스크립트, 현재 레벨 → 다음 레벨 붉은 오브) ==");
		UpgradeTable ut = UpgradeTable.fromWad(perm);
		for (String w : PlayerProgress.WEAPONS.reversed()) {
			Map<Integer, Integer> c = ut.costs.getOrDefault(w, Map.of());
			StringBuilder sb = new StringBuilder();
			c.forEach((lv, cost) -> sb.append(String.format("  L%d→L%d %5d", lv + 1, lv + 2, cost)));
			o.printf("%-38s%s   합계 %d%n", UpgradeTable.DISPLAY.get(w), sb, ut.total(w));
		}
		ut.others.forEach((k, v) -> o.printf("  (기타 대입) %s = %s%n", k, v));

		TweakIndex tw = TweakIndex.of(perm);
		String p = "/Player/";
		o.println();
		o.println("== /Player 성장 (TWK_Player) ==");
		o.print("Max Hit Points Level 0..4 :");
		for (int i = 0; i < 5; i++) {
			o.printf(" %.0f", tw.f("TWK_Player", p, "Max Hit Points Level " + i));
		}
		o.println();
		o.print("Max Magic Level 0..5      :");
		for (int i = 0; i < 6; i++) {
			o.printf(" %.0f", tw.f("TWK_Player", p, "Max Magic Level " + i));
		}
		o.println();
		o.printf("Shards Per Meter Level    : %d   (고르곤의 눈/불사조 깃털 최대 = 3 × 이 값)%n",
				tw.find("TWK_Player", p, "Shards Per Meter Level").raw());
		o.printf("Deaths Before Easy Offer  : %d%n", tw.find("TWK_Player", p, "Deaths Before Easy Offer").raw());
		o.print("Weapon Level 0..5 Dmg Mult:");
		for (int i = 0; i < 6; i++) {
			o.printf(" %.2f", tw.f("TWK_Player", p + "Weapon Level " + i + "/", "Dmg Mult"));
		}
		o.println();
		o.println("Costume  Damage  AIDamage  InfMagic  HealthOrb  MagicOrb  WeaponOrb");
		for (int i = 0; i < 6; i++) {
			String c = p + "Costume " + i + "/";
			o.printf("  %d      %5.2f  %6.2f    %6.2f    %5.2f     %5.2f     %5.2f%n", i,
					tw.f("TWK_Player", c, "Damage Mult"), tw.f("TWK_Player", c, "AI Damage Mult"),
					tw.f("TWK_Player", c, "Infinite Magic"), tw.f("TWK_Player", c, "Health Orb Mult"),
					tw.f("TWK_Player", c, "Magic Orb Mult"), tw.f("TWK_Player", c, "Weapon Orb Mult"));
		}
		o.println();
		o.println("== /GlobGame 난이도 (0x337728 + 난이도×0xC) ==");
		String[] diff = {"Easy", "Normal", "Hard", "Impossible"};
		String[] kor = {"인간(쉬움)", "전사(보통)", "영웅(어려움)", "신(매우 어려움)"};
		for (int i = 0; i < 4; i++) {
			String g = "/GlobGame/" + diff[i] + "/";
			o.printf("  %d %-10s %-14s Damage %.2f  AI Damage %.2f  AI Recovery %.2f%n", i, diff[i], kor[i],
					tw.f("TWK_GlobGame", g, "Damage Mult"), tw.f("TWK_GlobGame", g, "AI Damage Mult"),
					tw.f("TWK_GlobGame", g, "AI Recovery Time Mult"));
		}

		o.println();
		o.println("== 오브 템플릿 (R_PERMK TWK_Orb_*) ==");
		o.println("  tag           instance          type  points  spawn  life  attractT  attractR  go");
		for (String tag : tw.tags()) {
			if (!tag.startsWith("TWK_Orb_")) {
				continue;
			}
			String path = "/TweakTemplates/Orb/" + tag.substring(8) + "/";
			o.printf("  %-13s %-17s %4d  %6.1f  %5.2f  %4.1f  %8.2f  %8.1f  %s%n", tag,
					tw.s(tag, path, "Instance Name"), tw.lowByte(tag, path, "Type"), tw.f(tag, path, "Points"),
					tw.f(tag, path, "Spawn Time"), Float.intBitsToFloat(raw(tw, tag, path, 0x117c872e)),
					tw.f(tag, path, "Attract Time"), tw.f(tag, path, "Attract Radius"), tw.s(tag, path, "GO Name"));
		}
		o.println("  type: 0 체력(녹색) 1 마법(파랑) 2 경험(붉은). 획득량 = Points × Costume[c].{Health,Magic,Weapon} Orb Mult");
		o.println();
		emitters(tw, o);

		o.println();
		o.println("== 아이템 ID (SCR_AddItem/AddItem 0x1D8CF0, 비트 = ID-1) ==");
		ITEMS.forEach(l -> o.println("  " + l));

		o.println();
		o.println("== PlayerProgress (EE 0x304440, 0x90) ==");
		for (PlayerProgress.Field f : PlayerProgress.LAYOUT) {
			o.printf("  +0x%02X %-8s %-16s %s [%s]%n", f.offset(), f.type(), f.name(), f.desc(), f.certain() ? "확실" : "추정");
		}
		o.println();
		o.printf("== 세이브 파일 data0..4.bin (0x%X 바이트, 체크섬 = u32 합, 끝 워드) ==%n", SaveLayout.FILE_SIZE);
		for (SaveLayout.Block b : SaveLayout.STREAM) {
			o.printf("  %-15s %-6s %-34s %s%n", b.name(), b.size(), b.source(), b.desc());
		}
		o.println("  슬롯 디렉터리 (0x35D780 + 카드×0x44 + 슬롯×0x10):");
		for (PlayerProgress.Field f : SaveLayout.SLOT_ENTRY) {
			o.printf("    +0x%X %-4s %-10s %s%n", f.offset(), f.type(), f.name(), f.desc());
		}
	}

	/** OrbEmitter 템플릿: 종류별 (확률, 최소~최대 개수). 개수는 하위 바이트만 유효. */
	static void emitters(TweakIndex tw, PrintStream o) {
		o.println("== 오브 방출기 (TWK_OrbEmitter_*): 템플릿 H/M/W, 종류별 prob min~max, 임계 ==");
		for (String tag : tw.tags()) {
			if (!tag.startsWith("TWK_OrbEmitter_")) {
				continue;
			}
			String p = "/TweakTemplates/OrbEmitter/" + tag.substring(15) + "/";
			StringBuilder sb = new StringBuilder();
			for (String k : List.of("Normal Health", "Normal Magic", "Normal Weapon", "Low Health", "Low Magic")) {
				sb.append(String.format("  %s %.2f %d~%d", k, tw.f(tag, p, k + " Prob"), tw.lowByte(tag, p, k + " Min"),
						tw.lowByte(tag, p, k + " Max")));
			}
			o.printf("  %-20s %-16s tpl %d/%d/%d  thr H%.2f M%.2f%s%n", tag, tw.s(tag, p, "Instance Name"),
					tw.lowByte(tag, p, "Health Template"), tw.lowByte(tag, p, "Magic Template"), tw.lowByte(tag, p, "Weapon Template"),
					tw.f(tag, p, "Health Threshold"), tw.f(tag, p, "Magic Threshold"), sb);
		}
	}

	/** 이름 미복원 키 (#117c872e = 수명) 는 해시로 직접 찾는다. */
	private static int raw(TweakIndex tw, String tag, String path, int hash) {
		var e = tw.findHash(tag, path, hash);
		return e == null ? Float.floatToIntBits(Float.NaN) : e.raw();
	}

	/** AddItem 의 ID 별 처리 (점프 테이블 0x2B8190, 개수 0x2B81C0, 최대 0x2B81F0). */
	static final List<String> ITEMS = List.of(
			" 3 Lightning (포세이돈의 분노)  bit 0x0004  마법: 처음 얻으면 선택 마법으로, MP 레벨 0 이면 1 로",
			" 4 Medusa (메두사의 시선)       bit 0x0008  〃",
			" 5 Thunderbolt (제우스의 분노)  bit 0x0010  〃",
			" 6 RaiseDead (하데스의 군대)    bit 0x0020  〃",
			" 7 BerserkBlade (아르테미스의 검) bit 0x0040",
			" 9 Trident (포세이돈의 삼지창)  bit 0x0100  Player+0x2DC |= 0x200020 (수중 능력 추정)",
			"10 Gorgon Eye (고르곤의 눈)     +0x7F  최대 18. 6개마다 HP 레벨 +1",
			"11 Phoenix Feather (불사조 깃털) +0x80  최대 18. 6개마다 MP 레벨 +1 (MP 즉시 최대)",
			"14 SirenPiece (HUD 이름)        +0x81  최대 2 [추정]",
			"15 (AddItem 외) HP 완전 회복   16 MP 완전 회복   (0x1D9700)");

	static void levels(Path rom, PrintStream o) throws Exception {
		LevelGraph g = LevelGraph.scan(rom);
		o.println("== 씬 이동 호출 (WAD, 스크립트, 엔티티, 메시지, 상수 인자) ==");
		for (LevelGraph.Call c : g.calls) {
			o.printf("%-9s %-28s %-40s %-26s %s%n", c.wad(), c.script(), c.entity(),
					LevelGraph.MESSAGES.getOrDefault(c.msg(), "0x" + Integer.toHexString(c.msg())), c.args());
		}
		o.println();
		o.println("== WAD 연결 (Load/LoadWad/LoadForWarp/LoadCheck 첫 문자열 인자) ==");
		g.edges().forEach((k, v) -> o.printf("%-9s -> %s%n", k, v));
	}

	static void save(Path file, PrintStream o) throws Exception {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		o.printf("size=0x%X checksum %s, magic=%08x costume=%d%n", b.limit(),
				b.limit() >= SaveLayout.FILE_SIZE && SaveLayout.verify(b) ? "OK" : "불일치", b.getInt(0), b.getInt(4));
		int off = 8;
		for (int i = 0; i < 2; i++) {
			byte[] n = new byte[10];
			b.get(off, n);
			o.printf("wad[%d]=%s flag=%d%n", i, new String(n, 0, len(n)), b.get(off + 10));
			off += 11;
		}
		PlayerProgress pp = PlayerProgress.parse(b.slice(off, PlayerProgress.SIZE));
		o.printf("HP %.1f (Lv%d) MP %.1f (Lv%d) rage %.1f  red orbs %d  deaths %d  playTime %.0fs%n", pp.health(), pp.healthLevel(),
				pp.magic(), pp.magicLevel(), pp.rage(), pp.redOrbs(), pp.deaths(), pp.playTime());
		o.printf("weapon levels %s invested %s  eyes %d feathers %d items 0x%04x selected %d%n",
				Arrays.toString(pp.weaponLevels()), Arrays.toString(pp.investedOrbs()), pp.gorgonEyes(), pp.phoenixFeathers(),
				pp.itemFlags(), pp.selectedMagic());
	}

	private static int len(byte[] n) {
		int i = 0;
		while (i < n.length && n[i] != 0) {
			i++;
		}
		return i;
	}
}
