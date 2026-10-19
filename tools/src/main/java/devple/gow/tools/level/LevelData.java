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

	public LevelData(WadFile wad) {
		this.wad = wad;
		this.layout = new LevelLayout(wad);
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.MESH_EXTRA && t.hasData() && NcvCurve.is(t.name())) {
				curves.putIfAbsent(t.name(), NcvCurve.parse(t.name(), t.data()));
				continue;
			}
			if (t.tag() != WadTagType.RESOURCE || !t.hasData()) {
				continue;
			}
			ByteBuffer d = t.data();
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
