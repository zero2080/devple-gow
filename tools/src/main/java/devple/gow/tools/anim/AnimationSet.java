package devple.gow.tools.anim;

import devple.gow.tools.content.AnimOverrides;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 애니메이션 세트 ANM_ (매직 0x00000003) 와 외부 그룹 (매직 0x00010003).
 * <pre>
 * 0x00 u32 magic 3
 * 0x08 u32 flags         0x1000 회전 / 0x2000 이동 / 0x4000 스케일 애니메이션 포함, bit0 자동재생 추정
 * 0x0C u32 dataSize
 * 0x10 u16 dataTypeCount
 * 0x12 u16 groupCount
 * 0x14 u32 totalActCount
 * 0x18 u32 groupOffset[groupCount]
 *      DataType[dataTypeCount] { u16 typeId, u8 p1, u8 p2 }   (0 회전, 5 이동, 3 머티리얼색, 8 UV, 9 텍스처 시트, 10/11/12 부속)
 *        p2 = 그 항목이 act 에서 차지하는 상태 디스크립터 수 (타입 0 = 3: 회전·이동·스케일, 히어로 등 4) — 원작 채널 id = 앞 항목 p2 합
 *        (0x100EA8) [확실: 코드 + 데이터]. 타입 표시 "5 이동" 은 항목 번호 배치의 착시다 ({@link Animation#records}, 05 문서 §2.8.1)
 * 그룹: 0x00 u32 index, 0x04 u32 firstAct, 0x08 u32 flags (0x20000 = 외부), 0x0C u32 actCount,
 *       0x14 char name[24], 0x30 u32 actOffset[actCount] (그룹 기준)
 * 외부 그룹 (0x10003, 레벨 WAD 의 ANM_hero_Athn01A, R_MIN 의 ANM_MAIMIN 등):
 *   0x00 u32 magic, 0x04 u32 0, 0x08 부터 위와 같은 그룹 레코드 (index 로 본 세트의 그룹과 연결)
 * </pre>
 */
public final class AnimationSet {

	public static final int MAGIC = 0x00000003;
	public static final int MAGIC_EXTERNAL = 0x00010003;
	public static final int GROUP_EXTERNAL = 0x20000;

	public static final class Group {
		public final int index;
		public final int firstAct;
		public final int flags;
		public final String name;
		public final List<Animation> acts = new ArrayList<>();
		public String loadedFrom;

		Group(int index, int firstAct, int flags, String name) {
			this.index = index;
			this.firstAct = firstAct;
			this.flags = flags;
			this.name = name;
		}

		public boolean external() {
			return (flags & GROUP_EXTERNAL) != 0;
		}
	}

	public final String name;
	public final int flags;
	public final int[] typeIds;
	public final int[][] typeParams;
	public final int totalActs;
	public final List<Group> groups = new ArrayList<>();

	public AnimationSet(String name, ByteBuffer b) {
		this.name = name;
		flags = b.getInt(8);
		int nt = Short.toUnsignedInt(b.getShort(0x10));
		int ng = Short.toUnsignedInt(b.getShort(0x12));
		totalActs = b.getInt(0x14);
		typeIds = new int[nt];
		typeParams = new int[nt][2];
		int to = 0x18 + ng * 4;
		for (int i = 0; i < nt; i++) {
			typeIds[i] = Short.toUnsignedInt(b.getShort(to + i * 4));
			typeParams[i][0] = b.get(to + i * 4 + 2) & 0xFF;
			typeParams[i][1] = b.get(to + i * 4 + 3) & 0xFF;
		}
		for (int i = 0; i < ng; i++) {
			groups.add(readGroup(b, b.getInt(0x18 + i * 4), false));
		}
	}

	private Group readGroup(ByteBuffer b, int go, boolean forceActs) {
		Group g = new Group(b.getInt(go), b.getInt(go + 4), b.getInt(go + 8), Animation.cstr(b, go + 0x14, 24));
		if (!g.external() || forceActs) {
			int n = b.getInt(go + 0xC);
			for (int j = 0; j < n; j++) {
				g.acts.add(new Animation(g.name, b, go + b.getInt(go + 0x30 + j * 4), typeIds, recordCounts()));
			}
		}
		return g;
	}

	/** 항목마다 차지하는 act 상태 디스크립터 수 (p2 — 원작 채널 id 증분 0x100EA8, {@link Animation#records}). */
	int[] recordCounts() {
		int[] n = new int[typeIds.length];
		for (int i = 0; i < n.length; i++) {
			n[i] = typeParams[i][1];
		}
		return n;
	}

	/** 외부 그룹 리소스(0x10003)를 붙인다. index 가 맞는 외부 그룹을 채우면 true. */
	public boolean attachExternal(String resName, ByteBuffer b) {
		if (b.getInt(0) != MAGIC_EXTERNAL) {
			return false;
		}
		int idx = b.getInt(8);
		if (idx < 0 || idx >= groups.size() || !groups.get(idx).external()) {
			return false;
		}
		Group ext = readGroup(b, 8, true);
		Group mine = groups.get(idx);
		if (!mine.name.equalsIgnoreCase(ext.name)) {
			return false;
		}
		mine.acts.clear();
		mine.acts.addAll(ext.acts);
		mine.loadedFrom = resName;
		return true;
	}

	public List<Animation> all() {
		List<Animation> r = new ArrayList<>();
		groups.forEach(g -> r.addAll(g.acts));
		return r;
	}

	/**
	 * 이름으로 찾기 ("group/name" 또는 "name", 첫 일치). 모드 {@code anim.properties} 의 {@code replace.<이름>} 이 있고 그 클립이 이 세트에
	 * 있으면 그것 (49 문서 §12) — 엔진의 클립 이름 조회가 모두 여기를 지난다.
	 */
	public Animation find(String nm) {
		String r = AnimOverrides.replacement(name, nm);
		if (r != null) {
			Animation a = findOriginal(r);
			if (a != null) {
				return a;
			}
		}
		return findOriginal(nm);
	}

	/** 교체 없이 이름으로 찾기. */
	public Animation findOriginal(String nm) {
		String grp = null;
		int slash = nm.indexOf('/');
		if (slash >= 0) {
			grp = nm.substring(0, slash);
			nm = nm.substring(slash + 1);
		}
		for (Group g : groups) {
			if (grp != null && !g.name.equals(grp)) {
				continue;
			}
			for (Animation a : g.acts) {
				if (a.name.equals(nm)) {
					return a;
				}
			}
		}
		// 대소문자 무시 2차 검색 (TWK Navigation 표는 "standidle", "fallv" 처럼 소문자로 적혀 있다)
		for (Group g : groups) {
			if (grp != null && !g.name.equalsIgnoreCase(grp)) {
				continue;
			}
			for (Animation a : g.acts) {
				if (a.name.equalsIgnoreCase(nm)) {
					return a;
				}
			}
		}
		return null;
	}

	/** WAD 에서 ANM 세트를 읽고, 같은 WAD 의 0x10003 외부 그룹들을 연결한다. */
	public static AnimationSet load(WadFile wad, String anmName) {
		WadTag t = wad.resources().stream().filter(x -> x.name().equals(anmName) && x.magic() == MAGIC).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("ANM 세트 없음: " + anmName));
		AnimationSet s = new AnimationSet(t.name(), t.data());
		s.attachAll(wad);
		return s;
	}

	public void attachAll(WadFile wad) {
		for (WadTag x : wad.resources()) {
			if (x.magic() == MAGIC_EXTERNAL) {
				attachExternal(wad.name() + ":" + x.name(), x.data());
			}
		}
	}

	public static List<AnimationSet> loadAll(WadFile wad) {
		List<AnimationSet> r = new ArrayList<>();
		for (WadTag x : wad.resources()) {
			if (x.magic() == MAGIC && x.name().startsWith("ANM_")) {
				try {
					AnimationSet s = new AnimationSet(x.name(), x.data());
					s.attachAll(wad);
					r.add(s);
				} catch (RuntimeException e) {
					System.err.println("ANM 파싱 실패 " + x.name() + ": " + e);
				}
			}
		}
		return r;
	}
}
