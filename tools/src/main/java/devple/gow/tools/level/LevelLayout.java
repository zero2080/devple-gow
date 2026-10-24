package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 레벨 WAD 의 배치 구성: 오브젝트(0x40001, 관절 + 같은 그룹의 참조 리소스) 와 인스턴스(go*, 0x20001).
 * <pre>
 * GROUP{ RES obj (0x40001)  RES MDL_x(참조)  RES ANM_x  RES CDV_x  RES ESC_x  RES CAM_x ... }GROUP
 * GROUP{ RES goobj (0x20001) }GROUP
 * 관절 월드 = 관절 로컬(바인드) · 부모 … · 인스턴스 행렬.  정점(관절 로컬) · 관절 월드 = 월드 좌표
 * </pre>
 */
public final class LevelLayout {

	/**
	 * 0x40001 오브젝트 + 그룹 참조. tag = 오브젝트 리소스의 WAD 태그 번호 — 같은 이름으로 여러 번 정의된 참조 (FXC_/PTC_ …) 는 이 앞의
	 * 마지막 정의가 이 오브젝트의 것이다 ({@code FxResources#emitter(String, int)}).
	 */
	public record LevelObject(String name, Skeleton skeleton, List<String> refs, int tag) {

		public List<String> refs(String prefix) {
			return refs.stream().filter(r -> r.startsWith(prefix)).toList();
		}
	}

	/** 인스턴스 하나의 배치 결과: 관절별 월드 행렬 (인스턴스 포함). */
	public record Placement(GoInstance instance, LevelObject object, float[][] jointWorld) {

		public String model() {
			List<String> m = object.refs("MDL_");
			return m.isEmpty() ? null : m.getFirst();
		}
	}

	public final String wadName;
	public final Map<String, LevelObject> objects = new LinkedHashMap<>();
	public final List<GoInstance> instances = new ArrayList<>();
	/** 인스턴스가 없는 오브젝트 이름 (템플릿 / 스크립트 생성). */
	public final List<String> unplaced = new ArrayList<>();
	/**
	 * 인스턴스 이름(소문자) → 컨텍스트 이름 (`CXT_bvhall`, `CXT_default` …). WAD 끝의
	 * {@code GROUP{ RES CXT_x (서버 0x80000001)  GROUP{하위 서버}…  RES go… (크기 0) }GROUP} — 컨텍스트 소속 (43 문서 §11).
	 */
	public final Map<String, String> contextOf = new LinkedHashMap<>();

	public LevelLayout(WadFile wad) {
		this.wadName = wad.name();
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData()) {
				continue;
			}
			if (t.name().startsWith("CXT_") && t.magic() == 0x80000001) {
				for (int k = i + 1; k < tags.size(); k++) {
					WadTag s = tags.get(k);
					if (s.depth() < t.depth()) {
						break;
					}
					if (s.depth() == t.depth() && s.tag() == WadTagType.RESOURCE && !s.hasData()) {
						contextOf.putIfAbsent(s.name().toLowerCase(java.util.Locale.ROOT), t.name());
					}
				}
				continue;
			}
			if (t.magic() == Skeleton.MAGIC) {
				List<String> refs = new ArrayList<>();
				for (int k = i + 1; k < tags.size(); k++) {
					WadTag s = tags.get(k);
					if (s.depth() < t.depth()) {
						break;
					}
					if (s.depth() == t.depth() && s.tag() == WadTagType.RESOURCE) {
						if (s.hasData()) {
							break;
						}
						refs.add(s.name());
					}
				}
				objects.putIfAbsent(t.name(), new LevelObject(t.name(), new Skeleton(t.name(), t.data()), refs, i));
			} else if (t.magic() == GoInstance.MAGIC) {
				instances.add(GoInstance.parse(t.name(), t.data()));
			}
		}
		for (String o : objects.keySet()) {
			if (instances.stream().noneMatch(g -> g.object().equals(o))) {
				unplaced.add(o);
			}
		}
	}

	/** 인스턴스별 배치 (오브젝트를 찾지 못한 인스턴스는 제외). */
	public List<Placement> placements() {
		List<Placement> out = new ArrayList<>();
		for (GoInstance g : instances) {
			LevelObject o = objects.get(g.object());
			if (o == null) {
				continue;
			}
			out.add(new Placement(g, o, jointWorld(o.skeleton(), g.matrix())));
		}
		return out;
	}

	/** 바인드 로컬 기준 관절 월드 · 인스턴스. */
	public static float[][] jointWorld(Skeleton sk, float[] inst) {
		float[][] w = sk.bindWorld();
		for (int i = 0; i < w.length; i++) {
			w[i] = Mat4.mul(w[i], inst);
		}
		return w;
	}
}
