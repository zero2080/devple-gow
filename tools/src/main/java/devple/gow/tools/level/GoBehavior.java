package devple.gow.tools.level;

import java.nio.ByteBuffer;

/**
 * GO 의 비헤이비어 리소스 (BHV 서버 0x17, GO CXT 바로 뒤 같은 그룹). 0x28 바이트:
 * <pre>
 * 0x00 u32 0x17   0x04 char[20] 클래스 이름   0x20 s32 템플릿 번호   0x24 s32 id
 * </pre>
 * <table>
 * <tr><td>IO_CSM</td><td>상황 동작(문 차기·상자 열기·책 읽기). 템플릿 = TWK /TweakTemplates/CSM/&lt;n&gt;/ (애니 이름 #b50a547c), id = 과제(task) 번호:
 *   진행률 이벤트 1023 (인자 0 = 0..100 %, 인자 1 = id) 을 보낸다 (0x215928). ATHN01D gotwinkickdoor = CSM 44 "HydraKickDoor", 과제 206</td></tr>
 * <tr><td>PushPull</td><td>밀기/당기기·크랭크·레버. 템플릿 = /TweakTemplates/Push/&lt;n&gt;/, id = 붙잡기/놓기 이벤트 1048/1049 의 인자 0
 *   (PAND05B gopboxpand05b = Push 225 "Pandora's Box", id 503)</td></tr>
 * <tr><td>IO_TandF</td><td>들어 올리는 문 (Trident 당기기)</td></tr>
 * <tr><td>Breakable</td><td>부서지는 물체 (0x24 바이트)</td></tr>
 * </table>
 */
public record GoBehavior(String owner, String type, int template, int id) {

	public static boolean is(ByteBuffer d) {
		return d.limit() >= 0x24 && d.getInt(0) == 0x17;
	}

	public static GoBehavior parse(String owner, ByteBuffer d) {
		StringBuilder sb = new StringBuilder();
		for (int i = 4; i < 0x18; i++) {
			int c = d.get(i) & 0xFF;
			if (c == 0) {
				break;
			}
			sb.append((char) c);
		}
		int tpl = d.limit() >= 0x24 ? d.getInt(0x20) : 0;
		int id = d.limit() >= 0x28 ? d.getInt(0x24) : 0;
		return new GoBehavior(owner, sb.toString(), tpl, id);
	}

	/**
	 * WAD 안에서 GO 인스턴스 (CXT 0x5C 바이트) 이름이 goName (대소문자 무시) 인 것 바로 뒤 (같은 그룹) 의 비헤이비어. 없으면 null.
	 * 예: R_MIN gominstonedbrk → Breakable 517 "MIN StoneStatue" (35 문서 §2.2).
	 */
	public static GoBehavior find(devple.gow.tools.wad.WadFile wad, String goName) {
		String last = null;
		for (devple.gow.tools.wad.WadTag t : wad.tags()) {
			if (t.tag() != devple.gow.tools.wad.WadTagType.RESOURCE || !t.hasData() || t.name() == null) {
				continue;
			}
			ByteBuffer d = t.data();
			if (t.magic() == GoInstance.MAGIC && d.limit() == 0x5C) {
				last = t.name().trim();
				continue;
			}
			if (last != null && last.equalsIgnoreCase(goName) && t.magic() == 0x17 && is(d)) {
				return parse(last, d.order(java.nio.ByteOrder.LITTLE_ENDIAN));
			}
		}
		return null;
	}
}
