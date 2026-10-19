package devple.gow.tools.progress;

import devple.gow.tools.ui.FlpFile;
import devple.gow.tools.ui.FlpScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 무기/마법 업그레이드 비용표. 실행 파일이 아니라 {@code R_PERMK.WAD / FLP_HUDK} 의 Flash 스크립트에 상수로 있다.
 * <pre>
 *   Push "PS2_Blades_Level"; GetVariable; Push "0"; Equals; Not; If ..;
 *   Push "_level0/:BladesOrb_Capacity"; Push "1500"; SetVariable
 * </pre>
 * → "현재 레벨 n 에서 다음 레벨로 가는 데 필요한 붉은 오브 수". 실행 파일(Hud_RegisterFlashVars 0x1B6490)은
 * {@code *Orb_Capacity} 를 100(파워 오브 300)으로 초기화만 하고, 실제 값은 이 스크립트가 덮어쓴다.
 */
public final class UpgradeTable {

	/** HUD 변수 접두사 → 게임 내 이름. 순서 = 업그레이드 메뉴 PausePowerUp_State 1..6. */
	public static final Map<String, String> DISPLAY = Map.of(
			"Blades", "혼돈의 블레이드 (Blades of Chaos)",
			"Lightning", "포세이돈의 분노 (Lightning)",
			"Medusa", "메두사의 시선 (MedusaHead)",
			"Thunderbolt", "제우스의 분노 (Thunderbolt)",
			"RaiseDead", "하데스의 군대 (RaiseTheDead)",
			"BerserkBlade", "아르테미스의 검 (BerserkBlade)");

	/** 무기 접두사 → (현재 레벨 → 다음 레벨 비용). 레벨은 0 기반 (메뉴 표시 = +1). */
	public final Map<String, Map<Integer, Integer>> costs = new TreeMap<>();
	/** 그 밖의 숫자 상수 대입 (PowerUp_QuickDrain 등). */
	public final Map<String, List<String>> others = new TreeMap<>();

	private static final Pattern PUSH = Pattern.compile("^L[0-9a-f]+: Push \"(.*)\"$");
	private static final Pattern LEVEL_VAR = Pattern.compile("(?:_level0/:)?PS2_(\\w+)_Level");
	private static final Pattern CAP_VAR = Pattern.compile("(?:_level0/:)?(\\w+)Orb_Capacity");

	/** R_PERMK 의 모든 FLP 스크립트에서 비용표를 추출한다. */
	public static UpgradeTable fromWad(WadFile permk) {
		UpgradeTable t = new UpgradeTable();
		for (WadTag tag : permk.resources()) {
			if (tag.magic() != FlpFile.MAGIC) {
				continue;
			}
			FlpFile f = new FlpFile(tag.data());
			for (byte[] code : scripts(f)) {
				t.scan(FlpScript.disassemble(code, f::scriptStr));
			}
		}
		return t;
	}

	private static List<byte[]> scripts(FlpFile f) {
		List<byte[]> out = new ArrayList<>();
		List<FlpFile.Timeline> tls = new ArrayList<>(f.clips);
		tls.add(f.root);
		for (FlpFile.Sprite s : f.sprites) {
			tls.add(s.timeline());
			s.inputs().forEach(i -> out.add(i.script()));
		}
		for (FlpFile.Timeline tl : tls) {
			for (FlpFile.FrameLabel fl : tl.labels()) {
				out.addAll(fl.scripts());
			}
		}
		return out;
	}

	/**
	 * 직선 스캔. 마지막으로 본 "PS2_X_Level == n" 비교를 조건으로 기억하고, "XOrb_Capacity = 상수" 대입을 그 레벨의 비용으로 기록.
	 * 레벨 증가 직후 대입(PowerUpLoop)도 "새 레벨 == n" 비교 뒤에 오므로 같은 표가 나온다.
	 */
	void scan(List<String> lines) {
		String lvlVar = null;
		Integer lvl = null;
		for (int i = 0; i < lines.size(); i++) {
			String push = pushArg(lines.get(i));
			if (push == null) {
				continue;
			}
			Matcher lm = LEVEL_VAR.matcher(push);
			if (lm.matches() && i + 4 < lines.size() && lines.get(i + 1).endsWith("GetVariable")
					&& lines.get(i + 3).endsWith("Equals")) {
				String n = pushArg(lines.get(i + 2));
				if (n != null && n.matches("\\d+")) {
					lvlVar = lm.group(1);
					lvl = Integer.parseInt(n);
				}
				continue;
			}
			Matcher cm = CAP_VAR.matcher(push);
			if (cm.matches() && i + 2 < lines.size() && lines.get(i + 2).endsWith("SetVariable")) {
				String v = pushArg(lines.get(i + 1));
				if (v != null && v.matches("\\d+")) {
					String w = cm.group(1);
					if (w.equals(lvlVar) && lvl != null) {
						costs.computeIfAbsent(w, k -> new TreeMap<>()).merge(lvl, Integer.parseInt(v), (a, b) -> a);
					} else {
						others.computeIfAbsent(w + "Orb_Capacity", k -> new ArrayList<>()).add(v);
					}
				}
			}
		}
	}

	private static String pushArg(String line) {
		Matcher m = PUSH.matcher(line.strip());
		return m.matches() ? m.group(1) : null;
	}

	/** 레벨 n → n+1 비용 합계 (최대 레벨까지 누적). */
	public int total(String weapon) {
		return costs.getOrDefault(weapon, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
	}
}
