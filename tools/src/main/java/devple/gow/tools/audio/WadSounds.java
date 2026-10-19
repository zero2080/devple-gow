package devple.gow.tools.audio;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WAD 한 개의 사운드 리소스: SBlk 뱅크, VAG 팩, SEM 이미터, 그리고 GO 그룹(ESC ↔ SEM) 연결.
 *
 * @param groups ESC 이름(접두사 "ESC_" 제외, 24자 제한으로 앞이 잘릴 수 있음) → 같은 GO 에 붙은 이미터 이름들
 */
public record WadSounds(String wad, List<SoundBank> banks, List<VagPack> packs, Map<String, SoundEmitter> emitters,
						Map<String, List<String>> groups) {

	public static WadSounds load(WadFile wad) {
		List<SoundBank> banks = new ArrayList<>();
		List<VagPack> packs = new ArrayList<>();
		Map<String, SoundEmitter> emitters = new LinkedHashMap<>();
		Map<Integer, List<String>> siblings = new HashMap<>();
		for (WadTag t : wad.tags()) {
			if (t.tag() != WadTagType.RESOURCE) {
				continue;
			}
			if (!t.hasData()) {
				if (t.parent() != null) {
					siblings.computeIfAbsent(t.parent().offset(), k -> new ArrayList<>()).add(t.name());
				}
				continue;
			}
			if (t.size() < 8) {
				continue;
			}
			int magic = t.magic();
			if (magic == SoundBank.MAGIC && !t.name().startsWith("SEM_")) {
				banks.add(new SoundBank(t.name(), t.data()));
			} else if (magic == VagPack.MAGIC) {
				packs.add(new VagPack(t.name(), t.data()));
			} else if (SoundEmitter.isEmitter(t.name(), t.data())) {
				emitters.putIfAbsent(t.name(), SoundEmitter.parse(t.name(), t.data()));
			}
		}
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (List<String> sib : siblings.values()) {
			List<String> sems = sib.stream().filter(n -> n.startsWith("SEM_")).toList();
			if (sems.isEmpty()) {
				continue;
			}
			for (String n : sib) {
				if (n.startsWith("ESC_")) {
					groups.computeIfAbsent(n.substring(4), k -> new ArrayList<>()).addAll(sems);
				}
			}
		}
		return new WadSounds(wad.name(), banks, packs, emitters, groups);
	}

	/** SoundController 그룹 이름(엔티티 이름 접두사) → 이미터들. ESC 이름이 잘린 경우 접미사로 맞춘다. */
	public List<SoundEmitter> emittersOf(String group) {
		List<SoundEmitter> out = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : groups.entrySet()) {
			if (e.getKey().equalsIgnoreCase(group) || (e.getKey().length() >= 19 && group.endsWith(e.getKey()))) {
				for (String s : e.getValue()) {
					SoundEmitter em = emitters.get(s);
					if (em != null && !out.contains(em)) {
						out.add(em);
					}
				}
			}
		}
		return out;
	}
}
