import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;

public class Main implements LineListener {

    public static void main(String[] args) throws IOException {
        try {
            MOD mod = new MOD("/res/8bit_castle.mod", 4, 31);

            AudioFormat audioFormat = new AudioFormat(MOD.DATA_LINE_SAMPLE_RATE, 8, 1, true, false);
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getSourceDataLine(audioFormat);

            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
            
            sourceDataLine.addLineListener(new Main());

            byte[] musicData = mod.generatePCM();
            sourceDataLine.write(musicData, 0, musicData.length);
            
            System.out.println(sourceDataLine.getLongFramePosition());

            sourceDataLine.drain();
            sourceDataLine.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void update(LineEvent event) {
        System.out.println(event);
    }

}