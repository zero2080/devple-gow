package devple.gow.tools.wad;

import java.util.Map;

/**
 * 리소스 태그(0x1E) 데이터 첫 u32 매직 → 의미.
 * 0x8000_00XX 는 "서버(리소스 매니저) 선언", 그 외는 실제 리소스 데이터.
 * 상위 16비트는 서브타입/버전(예: FXC 0x000N001E, MDL 0x0002000F).
 */
public final class ResourceType {

	private static final Map<Integer, String> SERVERS = Map.ofEntries(
			Map.entry(0x01, "CXT (컨텍스트/게임오브젝트)"),
			Map.entry(0x03, "ANM (애니메이션)"),
			Map.entry(0x04, "SCR (스크립트)"),
			Map.entry(0x06, "LGT (라이트)"),
			Map.entry(0x07, "TXR (텍스처)"),
			Map.entry(0x08, "MAT (머티리얼)"),
			Map.entry(0x09, "CAM (카메라)"),
			Map.entry(0x0C, "GFX (GS 이미지/팔레트)"),
			Map.entry(0x0F, "MDL (모델)"),
			Map.entry(0x11, "COL (충돌)"),
			Map.entry(0x13, "PRT (파티클)"),
			Map.entry(0x14, "WYP (웨이포인트)"),
			Map.entry(0x17, "BHV (비헤이비어)"),
			Map.entry(0x18, "SND (사운드뱅크)"),
			Map.entry(0x1A, "EMT (이미터)"),
			Map.entry(0x1B, "WAD"),
			Map.entry(0x1C, "EEP"),
			Map.entry(0x1E, "FX (이펙트)"),
			Map.entry(0x21, "FLP (UI 레이아웃)"),
			Map.entry(0x23, "LINE"),
			Map.entry(0x27, "SHG (그림자 지오메트리)"));

	private ResourceType() {
	}

	public static boolean isServerDecl(int magic) {
		return (magic & 0xFFFF0000) == 0x80000000;
	}

	public static String describe(int magic) {
		String s = SERVERS.get(magic & 0xFFFF);
		String base = s != null ? s : String.format("?%04X", magic & 0xFFFF);
		if (isServerDecl(magic)) {
			return "server:" + base;
		}
		int sub = magic >>> 16;
		return sub != 0 ? base + " v" + sub : base;
	}
}
