package devple.gow.tools.fx;

import devple.gow.tools.wad.WadFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 레벨 환경 파라미터 (16 문서 §1). 실행 파일 생성자가 본문을 객체로 복사하는 오프셋 그대로 해석한다.
 * 스크립트 값의 거리 단위는 대부분 월드 ×1/16 (SCR_FarClip 은 값 ×16 이 뷰포트 far 평면).
 */
public final class EnvParams {

	/**
	 * SCR_Fog (생성자 0x20D2C0, 적용 0x20D3D0). 본문 0x44 바이트를 객체 +0x0C 로 복사.
	 *
	 * @param color     0x00 RGBA (Fog_R/G/B/A, A = 최대 불투명도)
	 * @param centreZ   0x10 int — GS Z 스케일 (뷰포트 +0x3A8, 기본 524287). 작을수록 안개가 가깝다
	 * @param scroll    0x14/0x18 안개 텍스처 스크롤 (초당, 화면 UV)
	 * @param rotDeg    0x1C 안개 텍스처 회전 (도/초)
	 * @param texScale  0x20 안개 텍스처 배율 [추정]
	 * @param material  0x24 char[24] 안개 텍스처 머티리얼 ("FOG" = TXR_FOG, 그 외 MAT_ + 이름의 첫 레이어 텍스처)
	 * @param blend     0x3C 0 = 블렌드 (GS 0x54, Ad 사용), 1 = 가산 (0x58), 2 = 감산 (0x52)
	 * @param backPlane 0x40 BackPlane_A (-1 = 끔, 0..128 = 배경(지오메트리 없는 곳) 안개 알파)
	 */
	public record Fog(String owner, float[] color, int centreZ, float[] scroll, float rotDeg, float texScale, String material,
					  int blend, int backPlane) {
	}

	/**
	 * SCR_LayeredFog (생성자 0x20D4F0, 적용 0x20D650). 본문 0x70 바이트. 색 3개 + 단계 위치 3개가 0x125A48 로 안개 관리자에 들어간다.
	 *
	 * @param colors  0x00 / 0x10 / 0x20 RGBA ×3
	 * @param stops   0x30..0x38 오름차순 위치 3개 (색 i 의 위치) [의미 추정: 화면 세로 그라디언트]
	 * @param centreZ 0x3C int
	 * @param scroll  0x40/0x44, rotDeg 0x48, texScale 0x4C, material 0x50 char[24], blend 0x68, backPlane 0x6C
	 */
	public record LayeredFog(String owner, float[][] colors, float[] stops, int centreZ, float[] scroll, float rotDeg,
							 float texScale, String material, int blend, int backPlane) {
	}

	/** SCR_FarClip (0x20CDA0): 0x00 f32 → 뷰포트 far = 값 × 16 (음수 = -Z 방향). */
	public record FarClip(String owner, float value) {

		public float farDistance() {
			return Math.abs(value * 16f);
		}
	}

	/** SCR_Sky (0x218920): 본문 = 하늘 오브젝트 이름 (카메라를 따라 그린다). */
	public record Sky(String owner, String object) {
	}

	/**
	 * SCR_Weather (생성자 0x21CBE0). 0x00 = 종류 (실행 파일 표 0x29F388: 0 gorain, 1 gosnow, 2 goplankton, 3 godust, 4 goembers, 5 gogroundfog).
	 *
	 * @param vec 0x04..0x0C (ATHN01A 12, 12, -12) [추정: 바람/낙하 속도 ×16]
	 * @param box 0x10..0x18 (70, 50, 63) — ×16 ×0.5 = 카메라 주변 생성 상자 반폭
	 */
	public record Weather(String owner, int type, float[] vec, float[] box) {

		public static final String[] TYPES = {"gorain", "gosnow", "goplankton", "godust", "goembers", "gogroundfog"};

		public String typeName() {
			return type >= 0 && type < TYPES.length ? TYPES[type] : "?" + type;
		}
	}

	/**
	 * SCR_Water (생성자 0x2192B8, 파문 높이장 시뮬레이션 0x21A758). 크기는 ×16 단위.
	 *
	 * @param size     0x00/0x04 X, Z 크기 (×16)
	 * @param cell     0x08 격자 간격 (×16, 보통 0.5 = 8)
	 * @param flag     0x0C (미상)
	 * @param material 0x10 char[24] 반사 환경맵 머티리얼 ("MAT_" + 이름, REFLENVM …)
	 * @param frames   0x28 1 = 머티리얼 프레임 사용 [추정]
	 * @param dropOff  0x2C/0x30 물방울 영역 오프셋 (×16), dropSize 0x34/0x38 영역 크기 (×16)
	 * @param dropRate 0x3C 초당 물방울 수, strength 0x40 충격 세기
	 */
	public record Water(String owner, float[] size, float cell, int flag, String material, int frames, float[] dropOff,
						float[] dropSize, int dropRate, float strength) {

		public int nx() {
			return Math.max(2, Math.round(size[0] / cell) + 1);
		}

		public int nz() {
			return Math.max(2, Math.round(size[1] / cell) + 1);
		}
	}

	/** 해석 전 스크립트 (SCR_Water, SCR_Mirror …): 값 영역 원본. */
	public record Raw(String target, String owner, EnvScript script) {
	}

	public final List<Fog> fogs = new ArrayList<>();
	public final List<LayeredFog> layeredFogs = new ArrayList<>();
	public final List<FarClip> farClips = new ArrayList<>();
	public final List<Sky> skies = new ArrayList<>();
	public final List<Weather> weathers = new ArrayList<>();
	public final List<Water> waters = new ArrayList<>();
	public final List<Raw> others = new ArrayList<>();
	/**
	 * SCR_Sky 인스턴스 스크립트가 붙은 GO 이름 (본문 크기와 무관). 원작 SCR_Sky 생성자 0x218920 / 매 프레임 0x218848 은 본문 (오브젝트 이름) 을 읽지 않고
	 * 스크립트의 소유 GO (+0x18) 를 카메라로 옮기고 그 모델들을 층 1 (0x218778 → 0x150738(모델, 1)) 로 둔다 [확실: 디스어셈블] —
	 * ATHN03A/03AA/03C {@code goaresanimgroup1} (MDL_AresAnimGroup1, 템플릿 SCR_Sky 없음) 처럼 본문 없는 인스턴스도 하늘이다.
	 * SCR_AresSky (0x218968 → 같은 0x218848 + 갱신 0x2189A8 무작위 act) 소유자는 {@link #aresSkyOwners}.
	 */
	public final List<String> skyOwners = new ArrayList<>();
	/**
	 * SCR_AresSky 인스턴스 스크립트가 붙은 GO 이름 (ATHN03D/03E goaresanimgroup1, 본문 0 바이트). 생성 0x218968 = 0x218848 (SCR_Sky 와 같은 하늘 층) +
	 * 객체 {f32 −1, s32 −1}, 매 프레임 0x2189A8 = act 가 끝나거나 감기면 다른 act 를 무작위로 (엔진 {@code level.AresSkyActs}, 43 §30).
	 */
	public final List<String> aresSkyOwners = new ArrayList<>();

	/** 인스턴스 값만 사용 (템플릿 기본값은 무시). */
	public EnvParams(WadFile wad) {
		this(EnvScript.load(wad));
	}

	public EnvParams(List<EnvScript> scripts) {
		for (EnvScript s : scripts) {
			if (!s.instance()) {
				if (s.target().equals("SCR_Sky") && s.size() > 0) {
					skies.add(new Sky(s.owner(), s.str(0, 32)));
				}
				continue;
			}
			switch (s.target()) {
				case "SCR_Fog" -> fogs.add(new Fog(s.owner(), s.vec(0, 4), s.i(0x10), s.vec(0x14, 2), s.f(0x1C), s.f(0x20),
						s.str(0x24, 24), s.i(0x3C), s.i(0x40)));
				case "SCR_LayeredFog" -> layeredFogs.add(new LayeredFog(s.owner(), new float[][]{s.vec(0, 4), s.vec(0x10, 4), s.vec(0x20, 4)},
						s.vec(0x30, 3), s.i(0x3C), s.vec(0x40, 2), s.f(0x48), s.f(0x4C), s.str(0x50, 24), s.i(0x68), s.i(0x6C)));
				case "SCR_FarClip" -> farClips.add(new FarClip(s.owner(), s.f(0)));
				case "SCR_Sky" -> {
					if (s.size() > 0) {
						skies.add(new Sky(s.owner(), s.str(0, 32)));
					}
					skyOwners.add(s.owner());
				}
				case "SCR_AresSky" -> {
					aresSkyOwners.add(s.owner());
					others.add(new Raw(s.target(), s.owner(), s));
				}
				case "SCR_Weather" -> weathers.add(new Weather(s.owner(), s.i(0), s.vec(4, 3), s.vec(0x10, 3)));
				case "SCR_Water" -> waters.add(new Water(s.owner(), s.vec(0, 2), s.f(8), s.i(0x0C), s.str(0x10, 24), s.i(0x28),
						s.vec(0x2C, 2), s.vec(0x34, 2), s.i(0x3C), s.f(0x40)));
				default -> others.add(new Raw(s.target(), s.owner(), s));
			}
		}
	}

	/** 사람이 읽는 요약 (fx:env). */
	public String describe() {
		StringBuilder b = new StringBuilder();
		for (Fog f : fogs) {
			b.append(String.format(Locale.ROOT, "Fog        %-18s rgba=%s centreZ=%d scroll=%s rot=%.2f scale=%.2f mat=%s blend=%d back=%d%n",
					f.owner(), v(f.color()), f.centreZ(), v(f.scroll()), f.rotDeg(), f.texScale(), f.material(), f.blend(), f.backPlane()));
		}
		for (LayeredFog f : layeredFogs) {
			b.append(String.format(Locale.ROOT, "LayeredFog %-18s c0=%s c1=%s c2=%s stops=%s centreZ=%d scroll=%s rot=%.2f scale=%.2f mat=%s blend=%d back=%d%n",
					f.owner(), v(f.colors()[0]), v(f.colors()[1]), v(f.colors()[2]), v(f.stops()), f.centreZ(), v(f.scroll()), f.rotDeg(),
					f.texScale(), f.material(), f.blend(), f.backPlane()));
		}
		for (FarClip f : farClips) {
			b.append(String.format(Locale.ROOT, "FarClip    %-18s value=%.2f → far %.0f%n", f.owner(), f.value(), f.farDistance()));
		}
		for (Sky s : skies) {
			b.append(String.format(Locale.ROOT, "Sky        %-18s object=%s%n", s.owner(), s.object()));
		}
		for (Weather w : weathers) {
			b.append(String.format(Locale.ROOT, "Weather    %-18s type=%d(%s) vec=%s box=%s%n", w.owner(), w.type(), w.typeName(), v(w.vec()), v(w.box())));
		}
		for (Water w : waters) {
			b.append(String.format(Locale.ROOT, "Water      %-18s size=%s cell=%.2f (%dx%d) flag=%d mat=%s frames=%d drop off=%s size=%s rate=%d str=%.2f%n",
					w.owner(), v(w.size()), w.cell(), w.nx(), w.nz(), w.flag(), w.material(), w.frames(), v(w.dropOff()), v(w.dropSize()),
					w.dropRate(), w.strength()));
		}
		for (Raw r : others) {
			b.append(String.format(Locale.ROOT, "%-10s %-18s %d bytes%n", r.target().replace("SCR_", ""), r.owner(), r.script().size()));
		}
		return b.toString();
	}

	static String v(float[] a) {
		StringBuilder b = new StringBuilder("(");
		for (int i = 0; i < a.length; i++) {
			b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.3g", a[i]));
		}
		return b.append(')').toString();
	}
}
