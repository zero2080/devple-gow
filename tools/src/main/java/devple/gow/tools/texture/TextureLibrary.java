package devple.gow.tools.texture;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WAD 하나의 GFX/PAL/TXR/MAT 인덱스와 이름 해석.
 * 같은 이름의 리소스가 여러 번 나오므로(R_PERMK 의 {@code TXR_file1} 등) 참조는
 * "참조하는 태그보다 앞에 있는 가장 가까운 같은 이름 리소스" 로 해석한다 (리소스는 의존 순서로 배치됨).
 */
public final class TextureLibrary {

	/** 해석된 텍스처. pal 이 null 이면 팔레트를 찾지 못함(다른 WAD 참조 등). */
	public record Entry(int tagIndex, TextureResource txr, GfxResource gfx, GfxResource pal, int mipLevel) {
	}

	private final List<WadTag> tags;
	private final Map<String, List<Integer>> byName = new HashMap<>();
	private final List<Entry> textures = new ArrayList<>();
	private final List<MaterialResource> materials = new ArrayList<>();

	public TextureLibrary(WadFile wad) {
		this.tags = wad.tags();
		List<Integer> txrIdx = new ArrayList<>();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.size() < 4) {
				continue;
			}
			byName.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(i);
			int m = t.magic();
			if (m == TextureResource.MAGIC && t.size() >= TextureResource.SIZE) {
				txrIdx.add(i);
			} else if (m == MaterialResource.MAGIC) {
				materials.add(MaterialResource.parse(t));
			}
		}
		for (int i : txrIdx) {
			TextureResource txr = TextureResource.parse(tags.get(i));
			GfxResource gfx = gfx(txr.gfx(), i);
			if (gfx == null) {
				continue;
			}
			int level = 0;
			String palName = txr.pal();
			int at = i;
			// 밉 레벨 TXR 은 PAL 이 비어 있음 → 자신을 nextMip 으로 가리키는 상위 TXR 의 팔레트 사용
			while (palName.isEmpty()) {
				int parent = findParent(tags.get(at).name(), at, txrIdx);
				if (parent < 0) {
					break;
				}
				level++;
				at = parent;
				palName = TextureResource.parse(tags.get(at)).pal();
			}
			GfxResource pal = palName.isEmpty() ? null : gfx(palName, i);
			textures.add(new Entry(i, txr, gfx, pal, level));
		}
	}

	private int findParent(String name, int at, List<Integer> txrIdx) {
		int best = -1;
		for (int j : txrIdx) {
			if (j != at && TextureResource.parse(tags.get(j)).nextMip().equals(name)) {
				if (best < 0 || Math.abs(j - at) < Math.abs(best - at)) {
					best = j;
				}
			}
		}
		return best;
	}

	/** 이름으로 GFX/PAL 해석 (ref 태그 앞의 가장 가까운 것 → 없으면 뒤쪽 첫 번째). */
	private GfxResource gfx(String name, int ref) {
		List<Integer> c = byName.get(name);
		if (c == null) {
			return null;
		}
		int pick = -1;
		for (int j : c) {
			if (tags.get(j).magic() != GfxResource.MAGIC) {
				continue;
			}
			if (j < ref || pick < 0) {
				pick = j;
			}
		}
		return pick < 0 ? null : GfxResource.parse(tags.get(pick));
	}

	public List<Entry> textures() {
		return textures;
	}

	public List<MaterialResource> materials() {
		return materials;
	}
}
