package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 레벨 WAD 하나의 배치 / 충돌 / 볼륨 / 카메라를 모은 것 (GL 비의존, 엔진·도구 공용).
 */
public final class LevelData {

	/** 배치된 볼륨: 볼륨 + 소유 오브젝트 관절 0 월드 행렬. */
	public record PlacedVolume(BallHull hull, DebugWire wire, String owner, float[] world, float[] inverse) {

		/** 월드 좌표 점이 볼륨(ball 또는 hull) 안인지. */
		public boolean contains(float x, float y, float z) {
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.contains(l[0], l[1], l[2]);
		}

		/** 월드 좌표 점을 포함하는 ball/hull 의 영역 번호들. */
		public java.util.Set<Integer> regions(float x, float y, float z) {
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.regionsAt(l[0], l[1], l[2]);
		}

		/**
		 * 영역 ID 가 있는 엔티티(원본 0x4A ≠ 0)의 볼륨 판정: 그 영역의 ball/hull 만.
		 * 볼륨에 그 영역이 없을 때: 영역이 여럿으로 나뉜 볼륨(예 ATHN01E ENV_bossEnt 51–54 의 "boss zone 5/6" 55/56)이면 밖,
		 * 영역이 하나뿐이면 볼륨 전체 (전 레벨 진입/이탈 센서 580 개 중 6 개, 데이터 불일치로 보고 기존 동작 유지).
		 */
		public boolean contains(float x, float y, float z, int region) {
			if (region == 0) {
				return contains(x, y, z);
			}
			if (!hull.hasRegion(region)) {
				return hull.regionCount() < 2 && contains(x, y, z);
			}
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.contains(l[0], l[1], l[2], region);
		}

		/** 월드 좌표 점을 포함하는 hull 번호 (-1 없음). */
		public int find(float x, float y, float z) {
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.find(l[0], l[1], l[2]);
		}
	}

	/** 배치된 카메라: 월드 행렬(행 0..2 = 오른쪽/위/뒤, 행 3 = 위치), 레일(NCV_, 월드 좌표, 없으면 null). */
	public record PlacedCamera(CameraDef def, String owner, float[] world, NcvCurve rail) {

		public float[] eye() {
			return new float[]{world[12], world[13], world[14]};
		}

		/** 바라보는 방향 = -Z 행 (정규화). */
		public float[] forward() {
			float x = -world[8], y = -world[9], z = -world[10];
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			return new float[]{x / l, y / l, z / l};
		}
	}

	public final WadFile wad;
	public final LevelLayout layout;
	public final List<CollisionSheet> sheets = new ArrayList<>();
	public final Map<String, BallHull> hulls = new LinkedHashMap<>();
	public final Map<String, DebugWire> wires = new LinkedHashMap<>();
	public final Map<String, CameraDef> cameras = new LinkedHashMap<>();
	/** 레일 커브 NCV_ (월드 좌표, MESH_EXTRA 0x70). */
	public final Map<String, NcvCurve> curves = new LinkedHashMap<>();
	public final List<PlacedVolume> volumes = new ArrayList<>();
	public final List<PlacedCamera> placedCameras = new ArrayList<>();
	/** GO 인스턴스 이름 → 줄 설정 (SCR_RopeSkinned). */
	public final Map<String, RopeDef> ropes = new LinkedHashMap<>();
	/** GO 인스턴스 이름 → 비헤이비어 (IO_CSM / PushPull / IO_TandF / Breakable). */
	public final Map<String, GoBehavior> behaviors = new LinkedHashMap<>();

	public LevelData(WadFile wad) {
		this.wad = wad;
		this.layout = new LevelLayout(wad);
		String lastGo = null;
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.MESH_EXTRA && t.hasData() && NcvCurve.is(t.name())) {
				curves.putIfAbsent(t.name(), NcvCurve.parse(t.name(), t.data()));
				continue;
			}
			if (t.tag() != WadTagType.RESOURCE || !t.hasData()) {
				continue;
			}
			ByteBuffer d = t.data();
			// GO CXT 바로 뒤 (같은 그룹) 의 SCR/BHV 는 그 GO 의 설정
			if (t.magic() == GoInstance.MAGIC && d.limit() == 0x5C) {
				lastGo = t.name().trim();
				continue;
			}
			if (lastGo != null && RopeDef.is(t.name(), d)) {
				ropes.putIfAbsent(lastGo, RopeDef.parse(lastGo, d));
				continue;
			}
			if (lastGo != null && t.magic() == 0x17 && GoBehavior.is(d)) {
				// GO 하나에 비헤이비어가 여럿일 수 있다 (PAND03G gologlever = Creator 100 + PushPull 15 "BMI Log Puller" id 351):
				// Creator 는 상호작용이 아니므로 다음 비헤이비어가 대신한다
				GoBehavior nb = GoBehavior.parse(lastGo, d);
				GoBehavior old = behaviors.get(lastGo);
				if (old == null || (old.type().equals("Creator") && !nb.type().equals("Creator"))) {
					behaviors.put(lastGo, nb);
				}
				continue;
			}
			if (t.magic() == 0x11) {
				if (CollisionSheet.is(d)) {
					sheets.add(new CollisionSheet(t.name(), d));
				} else if (BallHull.is(d)) {
					hulls.putIfAbsent(t.name(), new BallHull(t.name(), d));
				} else if (DebugWire.is(d)) {
					wires.putIfAbsent(t.name(), new DebugWire(t.name(), d));
				}
			} else if (t.magic() == CameraDef.MAGIC && t.size() >= 0xD0) {
				cameras.putIfAbsent(t.name(), CameraDef.parse(t.name(), d));
			}
		}
		for (LevelLayout.Placement p : layout.placements()) {
			float[] w = p.jointWorld()[0];
			for (String r : p.object().refs()) {
				BallHull h = hulls.get(r);
				if (h != null) {
					DebugWire dw = wires.get(r.substring(0, 2) + "Z" + r.substring(3));
					volumes.add(new PlacedVolume(h, dw, p.instance().name(), w, Mat4.invertAffine(w)));
				}
				CameraDef c = cameras.get(r);
				if (c != null) {
					// 카메라 이름과 같은 관절이 있으면 그 관절에 붙는다 (예: idlecamgroup 의 "IdleCam" 관절, ANM 으로 움직임)
					int j = p.object().skeleton().indexOf(r.substring(4));
					for (int k = 0; j < 0 && k < p.object().skeleton().size(); k++) {
						if (p.object().skeleton().joints.get(k).name().equalsIgnoreCase(r.substring(4))) {
							j = k;
						}
					}
					float[] jw = j > 0 ? p.jointWorld()[j] : w;
					// 레일은 월드 좌표 그대로 (ATHN01A Boat1Cam1 의 월드 위치가 NCV_Boat1Rail1 위에 정확히 놓인다)
					placedCameras.add(new PlacedCamera(c, p.instance().name(), Mat4.mul(c.local(), jw),
							c.rail() != null ? curves.get(c.rail()) : null));
				}
			}
		}
	}

	/** 카메라 이름(CAM_ 없이, 대소문자 무시)으로 배치 카메라 찾기. CMV 이름표의 "RCM_x" 를 그대로 넘겨도 된다. */
	public PlacedCamera camera(String name) {
		String n = name.startsWith("RCM_") ? name.substring(4) : name.startsWith("CAM_") ? name.substring(4) : name;
		for (PlacedCamera c : placedCameras) {
			if (c.def().name().substring(4).equalsIgnoreCase(n)) {
				return c;
			}
		}
		return null;
	}

	/**
	 * 가시 칸 컨텍스트 (43 문서 §11, 0x152EC8 → 0x16D6F0 / 0x16DC60 / 0x16D6F8): 카메라 눈 위치로 시트마다 보이는 컨텍스트 마스크를 만들고,
	 * 시트의 컨텍스트 목록에 있는데 비트가 꺼진 컨텍스트의 인스턴스 (소문자 이름) 를 돌려준다. 목록에 없는 컨텍스트 (CXT_default 등) 는 그대로 보인다.
	 */
	public java.util.Set<String> contextHidden(float x, float y, float z) {
		java.util.Set<String> hiddenCtx = new java.util.HashSet<>();
		for (CollisionSheet s : sheets) {
			long mask = s.visibleContextMask(x, y, z, 0);
			for (int i = 0; i < s.visContexts.size() && i < 64; i++) {
				if ((mask & (1L << i)) == 0) {
					hiddenCtx.add(s.visContexts.get(i).toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		if (hiddenCtx.isEmpty()) {
			return java.util.Set.of();
		}
		java.util.Set<String> out = new java.util.HashSet<>();
		for (Map.Entry<String, String> e : layout.contextOf.entrySet()) {
			if (hiddenCtx.contains(e.getValue().toLowerCase(java.util.Locale.ROOT))) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/**
	 * 0x20D0D8 (SCR_Fog / SCR_LayeredFog 갱신 0x20D220): 소유 GO 의 컨텍스트가 CXT_default 면 참, 아니면 눈 위치를 포함하는 칸 중 하나가
	 * 그 컨텍스트를 형식 2 로 켜야 참 (0x16DE18). 가시 시트가 없으면 참.
	 */
	public boolean contextActive(String instance, float x, float y, float z) {
		String ctx = layout.contextOf.get(instance.toLowerCase(java.util.Locale.ROOT));
		if (ctx == null || ctx.equalsIgnoreCase("CXT_default") || sheets.isEmpty()) {
			return true;
		}
		for (CollisionSheet s : sheets) {
			if (s.cellListsContext(x, y, z, ctx)) {
				return true;
			}
		}
		return false;
	}

	/** 월드 점이 들어 있는 카메라 볼륨(CMV) → 활성 카메라. 없으면 null. */
	public PlacedCamera activeCamera(float x, float y, float z) {
		for (PlacedVolume v : volumes) {
			if (v.hull().kind != 1) {
				continue;
			}
			int h = v.find(x, y, z);
			if (h >= 0) {
				String n = v.hull().nameOf(h);
				PlacedCamera c = n != null ? camera(n) : null;
				if (c != null) {
					return c;
				}
			}
		}
		return null;
	}
}
