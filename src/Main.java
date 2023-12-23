import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

public class Main {

    public static void main(String[] args) throws IOException {
        try {
            MOD mod = new MOD("/res/drozerix_-_ai_renaissance.mod", 4, 31);

            AudioFormat audioFormat = new AudioFormat(MOD.DATA_LINE_SAMPLE_RATE, 8, 1, true, false);
            SourceDataLine sourceDataLine = (SourceDataLine) AudioSystem.getSourceDataLine(audioFormat);

            sourceDataLine.open(audioFormat);
            sourceDataLine.start();
             
            byte[] musicData = mod.generatePCM();
            sourceDataLine.write(musicData, 0, musicData.length);
            
            sourceDataLine.drain();
            sourceDataLine.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}