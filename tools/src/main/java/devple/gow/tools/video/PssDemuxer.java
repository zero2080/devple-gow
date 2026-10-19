package devple.gow.tools.video;

import java.io.IOException;

/**
 * PSS/PSW = MPEG-2 프로그램 스트림 (18 문서 §2) 디먹서.
 * <pre>
 * 팩(0x000001BA, MPEG-2 14바이트 헤더) 16KB 정렬, 팩마다 PES 4개 내외:
 *   0xE0       MPEG-2 비디오 ES
 *   0xBD       private_stream_1 — 페이로드 앞 4바이트 서브헤더 (FF A0 00 00 = SS 오디오 #0) 뒤에 SShd/SSbd 오디오
 *   0xBE       패딩, 0xBB 시스템 헤더 (첫 팩)
 *   0x000001B9 프로그램 끝
 * </pre>
 * 순차 읽기 전용. {@link #next()} 는 비디오/오디오 PES 의 페이로드만 돌려준다 (반환 배열은 다음 호출에서 재사용).
 */
public final class PssDemuxer {

	public static final int VIDEO = 0xE0;
	public static final int PRIVATE1 = 0xBD;

	/** PES 페이로드. pts = 90kHz 틱 (없으면 -1). sub = private_stream_1 서브 스트림 id (0xFF..), 비디오는 -1. */
	public static final class Packet {
		public int stream;
		public int sub;
		public long pts;
		public byte[] data = new byte[0x10000];
		public int len;
	}

	private final MovieSource src;
	private final byte[] buf = new byte[0x40000];
	private final Packet pkt = new Packet();
	private long bufPos;    // buf[0] 의 파일 오프셋
	private int bufLen;
	private int p;          // buf 내 현재 위치
	private boolean end;

	public PssDemuxer(MovieSource src) {
		this.src = src;
	}

	/** 처음으로 되감기. */
	public void rewind() {
		bufPos = 0;
		bufLen = 0;
		p = 0;
		end = false;
	}

	/** 현재 파일 오프셋. */
	public long position() {
		return bufPos + p;
	}

	private boolean ensure(int n) throws IOException {
		if (p + n <= bufLen) {
			return true;
		}
		System.arraycopy(buf, p, buf, 0, bufLen - p);
		bufPos += p;
		bufLen -= p;
		p = 0;
		while (bufLen < n) {
			int r = src.read(bufPos + bufLen, buf, bufLen, buf.length - bufLen);
			if (r <= 0) {
				return false;
			}
			bufLen += r;
		}
		return true;
	}

	/** 다음 비디오(0xE0) 또는 private_stream_1(0xBD) 패킷. 끝이면 null. */
	public Packet next() throws IOException {
		while (!end) {
			if (!ensure(6)) {
				return null;
			}
			if (buf[p] != 0 || buf[p + 1] != 0 || buf[p + 2] != 1) {
				// 동기 상실: 다음 시작 코드까지 건너뛴다
				p++;
				continue;
			}
			int sid = buf[p + 3] & 0xFF;
			if (sid == 0xBA) {
				if (!ensure(14)) {
					return null;
				}
				if ((buf[p + 4] & 0xC0) == 0x40) {
					p += 14 + (buf[p + 13] & 7);   // MPEG-2 팩 헤더 + 스터핑
				} else {
					p += 12;                        // MPEG-1 팩 헤더
				}
				continue;
			}
			if (sid == 0xB9) {
				end = true;
				return null;
			}
			if (sid < 0xBB) {
				p++;
				continue;
			}
			int len = ((buf[p + 4] & 0xFF) << 8) | (buf[p + 5] & 0xFF);
			if (!ensure(6 + len)) {
				return null;
			}
			int body = p + 6;
			p = body + len;
			if (sid != VIDEO && sid != PRIVATE1) {
				continue;
			}
			// MPEG-2 PES 헤더: 10xx xxxx, PTS_DTS_flags, header_data_length
			int flags = buf[body + 1] & 0xFF;
			int hl = buf[body + 2] & 0xFF;
			int pay = body + 3 + hl;
			pkt.pts = (flags & 0x80) != 0 ? pts(body + 3) : -1;
			pkt.stream = sid;
			pkt.sub = -1;
			int n = body + len - pay;
			if (sid == PRIVATE1) {
				pkt.sub = buf[pay] & 0xFF;
				pay += 4;
				n -= 4;
			}
			if (n <= 0) {
				continue;
			}
			System.arraycopy(buf, pay, pkt.data, 0, n);
			pkt.len = n;
			return pkt;
		}
		return null;
	}

	private long pts(int o) {
		return ((long) ((buf[o] >> 1) & 7) << 30) | ((buf[o + 1] & 0xFFL) << 22) | (((buf[o + 2] & 0xFF) >> 1) << 15)
				| ((buf[o + 3] & 0xFFL) << 7) | ((buf[o + 4] & 0xFF) >> 1);
	}
}
