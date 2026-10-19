package devple.gow.tools.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 디코딩된 PCM16. samples 는 채널 인터리브(L R L R ...). */
public record PcmSound(String name, int sampleRate, int channels, short[] samples) {

	public void writeWav(OutputStream os) throws IOException {
		int dataLen = samples.length * 2;
		ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		h.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes())
				.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
				.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
				.put("data".getBytes()).putInt(dataLen);
		os.write(h.array());
		ByteBuffer d = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN);
		for (short s : samples) {
			d.putShort(s);
		}
		os.write(d.array());
	}

	public byte[] toWav() throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		writeWav(bos);
		return bos.toByteArray();
	}
}
