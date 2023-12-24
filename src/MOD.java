import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MOD Music PCM Generator
 * 
 * References:
 * https://hornet.org/code/audio/docs/fmoddoc2.zip
 * https://github.com/Prezzodaman/pymod
 * https://github.com/NardJ/ModTrack-for-Python/tree/master/mod_refs
 * 
 * @author Leonardo Ono (ono.leo80@gmail.com)
 */
public class MOD {

    public static final int DATA_LINE_SAMPLE_RATE = 44100;

    public static final int AMIGA_CLOCK = 7159090;
    
    private static class Sample {

        final int length;
        int fineTune; // can be modified using extended effect 0x5
        final int volume;
        final int loopStart;
        final int loopLength;
        final int loopEnd;
        final boolean useLoop;
        byte[] sampleData;
        
        public Sample(int length, int fineTune, int volume, int loopStart, int loopLength) {
            this.length = length;
            this.fineTune = fineTune;
            this.volume = volume;
            this.loopStart = loopStart;
            this.loopLength = loopLength;
            this.loopEnd = loopStart + loopLength - 1;
            this.useLoop = loopLength > 2;
        }

    }

    private static class PatternNote {

        final int sampleNumber;
        final int samplePeriodValue;
        final int effectNumber;
        final int effectParameters;
    
        public PatternNote(long value) {
            sampleNumber = (int) (((value & 0xf0000000) >> 24) + ((value & 0xf000) >> 12));
            samplePeriodValue = (int) ((value & 0xfff0000) >> 16);
            effectNumber = (int) ((value & 0xf00) >> 8);
            effectParameters = (int) (value & 0xff);
        }

    }

    private static class Channel {
    
        Sample sample;
    
        //int period;
        double notePeriod;

        double noteFrequency;
        double pitchFactor;
    
        double lastSampleIndex;
        double sampleIndex;
        double sampleIndexInc;
        
        int volume;
        int hardwareVolume;
        double volumeFactor;
    
        // effects
        int portaSpeed;
        int noteToPortaTo;
    
        int vibratoPos; // -32 to +31
        int vibratoDepth;
        int vibratoSpeed;
        int vibratoWavControl;
    
        int tremoloPos; // -32 to +31
        int tremoloDepth;
        int tremoloSpeed;
        int tremoloWavControl;
     
        public void setSample(Sample sample) {
            this.sample = sample;
        }
        
        public void setHardwareVolume(int volume) {
            if (volume < 0) volume = 0;
            if (volume > 64) volume = 64;
            this.hardwareVolume = volume;
            this.volumeFactor = volume / 64.0;
        }
        
        public void setNotePeriod(double period) {
            this.notePeriod = period;
            this.noteFrequency = AMIGA_CLOCK / (2.0 * period);
            int fineTune = 0;
            if (sample != null) {
                fineTune = sample.fineTune;
            }
            noteFrequency = noteFrequency * Math.pow(2.0, (1.0 / 12.0 / 8.0) * fineTune);
        }
        
        public void setHardwareFrequency(double noteFrequency) {
            this.pitchFactor = noteFrequency / DATA_LINE_SAMPLE_RATE;
            this.sampleIndexInc = pitchFactor;
        }
        
        public byte getNextSample() {
            byte nextSample = 0;
    
            if (sample == null) {
                return nextSample;
            }
            
            int sampleIndexInt = (int) sampleIndex;
    
            if (sample.useLoop) {
                // before loop
                int loopEnd = Math.min(sample.loopEnd, sample.sampleData.length);
                if (sampleIndexInt <= loopEnd) {
                    nextSample = sample.sampleData[sampleIndexInt];
                }
                // after loop
                else {
                    nextSample = sample.sampleData[sample.loopStart 
                        + ((sampleIndexInt - sample.loopEnd) % sample.loopLength)];
                }
            }
            else if (sampleIndexInt < sample.sampleData.length) {
                nextSample = sample.sampleData[sampleIndexInt];
            }
    
            sampleIndex += sampleIndexInc;
            return (byte) (nextSample * volumeFactor);
        }
        
        private MOD mod;
        private PatternNote lastNote;
    
        public void triggerNote(MOD mod, PatternNote note) {
            this.mod = mod;
            this.lastNote = note;
    
            if (note.sampleNumber > 0) {
                Sample sample = mod.samples[note.sampleNumber - 1];
                setSample(sample);
                setHardwareVolume(sample.volume);
                volume = sample.volume;
                sampleIndex = 0; 
            }
            
            if (note.samplePeriodValue > 0) {
                if (vibratoWavControl < 4) {
                    vibratoPos = 0;
                } 
    
                if (tremoloWavControl < 4) {
                    tremoloPos = 0;
                } 
    
                //double nf = AMIGA_CLOCK / (2.0 * note.samplePeriodValue);
                //nf = nf * Math.pow(2.0, (1.0 / 12.0 / 8.0) * sample.fineTune);
                //period = (int) (AMIGA_CLOCK / (2 * nf));
                
                if (note.effectNumber != 3 && note.effectNumber != 5) {
                    setNotePeriod(note.samplePeriodValue);
                    setHardwareFrequency(noteFrequency);
                }
            }    
        }

        // tick 0
        public void startEffect(PatternNote note) {
            switch (note.effectNumber) {
                case 0x3 -> { // porta to note (glissando)
                    if (note.effectParameters > 0) {
                        portaSpeed = note.effectParameters;
                    }
                    if (note.samplePeriodValue > 0) {
                        noteToPortaTo = note.samplePeriodValue;
                    }
                }
    
                case 0x4 -> { // vibrato
                    int vibratoSpeed = (note.effectParameters & 0xf0) >> 4;
                    if (vibratoSpeed > 0) {
                        this.vibratoSpeed = vibratoSpeed;
                    }
                    int vibratoDepth = note.effectParameters & 0xf;
                    if (vibratoDepth > 0) {
                        this.vibratoDepth = vibratoDepth;
                    }
                }
    
                case 0x7 -> { // tremolo
                    int tremoloSpeed = (note.effectParameters & 0xf0) >> 4;
                    if (tremoloSpeed > 0) {
                        this.tremoloSpeed = tremoloSpeed;
                    }
                    int tremoloDepth = note.effectParameters & 0xf;
                    if (tremoloDepth > 0) {
                        this.tremoloDepth = tremoloDepth;
                    }
                }
    
                case 0x9 -> { // sample offset
                    if (note.effectParameters != 0) {
                        sampleIndex = note.effectParameters << 8;
                        lastSampleIndex = sampleIndex;
                    }
                    else {
                        sampleIndex = lastSampleIndex;
                    }
                }
    
                case 0xc -> { // set volume
                    setHardwareVolume(note.effectParameters);
                    volume = note.effectParameters;
                }
    
                case 0xe -> { // extended effects
                    int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                    int extendedValue = note.effectParameters & 0xf;
                    switch (extendedEffectId) {
                        case 0x1 -> { // fine portamento up
                            noteFrequency -= note.effectParameters;
                            if (noteFrequency < AMIGA_CLOCK / (2 * 108.0)) {
                                noteFrequency = AMIGA_CLOCK / (2 * 108.0);
                            }
                            setHardwareFrequency(noteFrequency);
                        }
    
                        case 0x2 -> { // fine portamento down    
                            noteFrequency += note.effectParameters;
                            if (noteFrequency > AMIGA_CLOCK / (2 * 907.0)) {
                                noteFrequency = AMIGA_CLOCK / (2 * 907.0);
                            }
                            setHardwareFrequency(noteFrequency);
                        }
    
                        case 0x4 -> { // set vibrato waveform
                            vibratoWavControl = extendedValue;
                        } 
    
                        case 0x5 -> { // set instrument finetune
                            sample.fineTune = extendedValue > 7 ? extendedValue - 16 : extendedValue;
                        }
    
                        case 0x7 -> { // set tremolo waveform
                            tremoloWavControl = extendedValue;
                        }
    
                        case 0xa -> { // fine volume slide up
                            int volumeSlide = extendedValue;
                            volume = hardwareVolume + volumeSlide;
                            if (volume > 64) volume = 64;
                            setHardwareVolume(volume);
                        }
    
                        case 0xb -> { // fine volume slide down
                            int volumeSlide = extendedValue;
                            volume = hardwareVolume - volumeSlide;
                            if (volume < 0) volume = 0;
                            setHardwareVolume(volume);
                        }
                    }
                }
            }
        }
    
        public void updateEffect(int tick, PatternNote note) {
            switch (note.effectNumber) {
                case 0x0 -> { // arpeggio
                    switch ((tick - 1) % 3) {
                        case 0 -> setHardwareFrequency(noteFrequency);
    
                        case 1 -> {
                            int n = (note.effectParameters & 0xf0) >> 4;
                            setHardwareFrequency(noteFrequency * Math.pow(2, n / 12.0));
                        }
    
                        case 2 -> {
                            int n = (note.effectParameters & 0xf);
                            setHardwareFrequency(noteFrequency * Math.pow(2, n / 12.0));
                        }
                    }
                }
    
                case 0x1 -> { // slide up (portamento up)
                    notePeriod -= note.effectParameters;
                    if (notePeriod < 108) {
                        notePeriod = 108;
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);
                }
                
                case 0x2 -> { // slide down (portamento down)
                    notePeriod += note.effectParameters;
                    if (notePeriod > 907) {
                        notePeriod = 907;
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);
                }
    
                case 0x3 -> { // porta to note (glissando)
                    double sign = noteToPortaTo - notePeriod;
                    if (sign > 0) {
                        notePeriod += portaSpeed;
                        if (notePeriod > noteToPortaTo) {
                            notePeriod = noteToPortaTo;
                        }
                    }
                    else if (sign < 0) {
                        notePeriod -= portaSpeed;
                        if (notePeriod < noteToPortaTo) {
                            notePeriod = noteToPortaTo;
                        }
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);
                }
    
                case 0x4 -> { // vibrato
                    vibratoPos += vibratoSpeed;
                    double x = (vibratoPos / 64.0) * 2 * Math.PI;
                    int n = (int) (vibratoDepth * 2.0 * Math.sin(x));
                    setHardwareFrequency(noteFrequency * Math.pow(2.0, (1.0 / 12.0 / 16.0) * n));
                }

                // TODO: temporary 
                case 0x5 -> { // porta + volume slide
                    double sign = noteToPortaTo - notePeriod;
                    if (sign > 0) {
                        notePeriod += portaSpeed;
                        if (notePeriod > noteToPortaTo) {
                            notePeriod = noteToPortaTo;
                        }
                    }
                    else if (sign < 0) {
                        notePeriod -= portaSpeed;
                        if (notePeriod < noteToPortaTo) {
                            notePeriod = noteToPortaTo;
                        }
                    }
                    setNotePeriod(notePeriod);
                    setHardwareFrequency(noteFrequency);

                    int volumeSlide = 0;
                    int up = (note.effectParameters & 0xf0) >> 8;
                    int down = (note.effectParameters & 0xf);
                    if (up > 0) volumeSlide = up;
                    if (down > 0) volumeSlide = -down;
                    if (up > 0 && down > 0) volumeSlide = 0;
                    setHardwareVolume(hardwareVolume + volumeSlide);
                    volume = hardwareVolume;
                }

                // TODO: temporary 
                case 0x6 -> { // vibrato + volume slide
                    vibratoPos += vibratoSpeed;
                    double x = (vibratoPos / 64.0) * 2 * Math.PI;
                    int n = (int) (vibratoDepth * 2.0 * Math.sin(x));
                    setHardwareFrequency(noteFrequency * Math.pow(2.0, (1.0 / 12.0 / 16.0) * n));

                    int volumeSlide = 0;
                    int up = (note.effectParameters & 0xf0) >> 8;
                    int down = (note.effectParameters & 0xf);
                    if (up > 0) volumeSlide = up;
                    if (down > 0) volumeSlide = -down;
                    if (up > 0 && down > 0) volumeSlide = 0;
                    setHardwareVolume(hardwareVolume + volumeSlide);
                    volume = hardwareVolume;
                }
    
                case 0x7 -> { // tremolo
                    tremoloPos += tremoloSpeed;
                    double x = (tremoloPos / 64.0) * 2 * Math.PI;
                    int n = (int) (tremoloDepth * 4.0 * Math.sin(x));
                    setHardwareVolume(volume + n);
                }
    
                case 0xa -> { // volume slide
                    int volumeSlide = 0;
                    int up = (note.effectParameters & 0xf0) >> 8;
                    int down = (note.effectParameters & 0xf);
                    if (up > 0) volumeSlide = up;
                    if (down > 0) volumeSlide = -down;
                    if (up > 0 && down > 0) volumeSlide = 0;
                    setHardwareVolume(hardwareVolume + volumeSlide);
                    volume = hardwareVolume;
                }
    
                case 0xe -> { // extended effects
                    int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                    int extendedValue = note.effectParameters & 0xf;
                    switch (extendedEffectId) {
                        case 0x9 -> { // retrigger sample
                            if (tick % extendedValue == 0) {
                                triggerNote(mod, lastNote);
                            }
                        }
    
                        case 0xc -> { // cut note
                            if (tick == extendedValue) {
                                volume = 0;
                                setHardwareVolume(volume);
                            }
                        }
                    }
                }
            } 
        }
    }
    
    private int channelsNum = 0;
    private int songLength;
    private int samplesCount;
    private Sample[] samples;
    private int[] patternOrderTable = new int[128];
    private PatternNote[][][] notes; // [pattern][row][channel]
    private int BPM = 125; // 50hz, or 50 ticks per second for 125 BPM. Formula: HZ = (2 * BPM) / 5
    private double ticksPerSecond = (2 * BPM) / 5.0;
    private int samplesPerTick = (int) (DATA_LINE_SAMPLE_RATE * (1.0 / ticksPerSecond));
    private int speed = 6; // default speed = 6 ticks per row
    private Channel[] channels;

    public MOD(String filename, int channelsNum, int samplesCount) {
        this.channelsNum = channelsNum;
        this.samplesCount = samplesCount;
        loadMOD(filename);
        channels = new Channel[channelsNum];
        for (int i = 0; i < channelsNum; i++) {
            channels[i] = new Channel();
        }
    }

    private void loadMOD(String filename) {
        ByteBuffer bb = null;
        try (InputStream is = getClass().getResourceAsStream(filename)) {
            bb = ByteBuffer.wrap(is.readAllBytes());
            bb.order(ByteOrder.BIG_ENDIAN);
        }
        catch (Exception e) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, "can't load mod file !", e);
            System.exit(-1);
        }

        // extract samples info
        samples = new Sample[samplesCount];
        bb.position(20);
        for (int i = 0; i < samplesCount; i++) {
            bb.position(bb.position() + 22); // skip name
            int sampleLength = 2 * (bb.getShort() & 0xffff);
            int sampleFineTune = bb.get() & 0xff;
            sampleFineTune = sampleFineTune > 7 ? sampleFineTune - 16 : sampleFineTune;
            int sampleVolume = bb.get() & 0xff;
            int sampleLoopStart = 2 * (bb.getShort() & 0xffff);
            int sampleLoopLength = 2 * (bb.getShort() & 0xffff);
            Sample sample = new Sample(sampleLength, sampleFineTune, sampleVolume, sampleLoopStart, sampleLoopLength);
            samples[i] = sample;
        }

        songLength = bb.get() & 0xff;
        bb.get(); // unused byte

        int patternsCount = 0;
        for (int i = 0; i < 128; i++) {
            patternOrderTable[i] = bb.get() & 0xff;
            if (patternOrderTable[i] > patternsCount) {
                patternsCount = patternOrderTable[i];
            }
        }

        // discard 4 bytes (signature, offset 1080)
        bb.getInt(); // "M.K.", etc
        
        // extract all patterns notes
        notes = new PatternNote[++patternsCount][64][channelsNum];
        for (int p = 0; p < patternsCount; p++) {
            for (int r = 0; r < 64; r++) {
                for (int n = 0; n < channelsNum; n++) {
                    notes[p][r][n] = new PatternNote((long) (bb.getInt() & 0xffffffff));
                }
            }
        }

        // extract samples
        for (int i = 0; i < samplesCount; i++) {
            Sample sample = samples[i];
            sample.sampleData = new byte[sample.length];
            bb.get(sample.sampleData);
        }
    }

    private Stack<Integer> loopRowStack = new Stack<>();
    private Stack<Integer> loopCountStack = new Stack<>();

    private void startEffect(PatternNote note, int orderTableIndex, int currentRow) {
        switch (note.effectNumber) {
            case 0xb -> { // jump to pattern
                lastJumpToPatternRow = currentRow;
                startRow = 0;
                startPattern = note.effectParameters;
                if (startPattern < 0) startPattern = 0;
                
                // TODO: can't loop from last to first pattern.
                //       since this is not streaming, it will get stuck when generating pcm
                //if (startPattern > songLength - 1) startPattern = 0;

                // TODO: how to force exit from infinite loop properly?
                if (orderTableIndex == songLength - 1 && startPattern == 0) startPattern = songLength;

                breakPattern = true;
            }

            case 0xd -> { // pattern break
                startRow = 10 * ((note.effectParameters & 0xf0) >> 4) + (note.effectParameters & 0xf);
                if (startRow < 0) startRow = 0;
                if (startRow > 63) startRow = 0;

                // ref: FMODDOC.TXT - 5.12 Effect Bxy (Jump To Pattern)
                // You should set a flag to say there has been a pattern jump, so if there
                // is a pattern break on the same row, the pattern break effect will not
                // increment the order.  I know its strange but it is a protracker feature.
                if (currentRow != lastJumpToPatternRow) startPattern = orderTableIndex + 1;
                
                // TODO: can't loop from last to first pattern.
                //       since this is not streaming, it will get stuck when generating pcm
                //if (startPattern > songLength - 1) startPattern = 0;

                breakPattern = true;
            }

            case 0xe -> { // extended effects
                int extendedEffectId = (note.effectParameters & 0xf0) >> 4;
                int extendedValue = note.effectParameters & 0xf;
                switch (extendedEffectId) {

                    // check ODE2PTK.MOD order 4 (pattern #1)
                    // nested pattern loop's:
                    // row 18
                    //    row 24
                    //    row 25
                    // row 31
                    case 0x6 -> { // pattern loop
                        if (extendedValue == 0) {
                            if (!loopRowStack.contains(currentRow)) {
                                loopRowStack.push(currentRow);
                                loopCountStack.push(loopCount);
                                loopCount = 0;
                            }
                        }
                        else {
                            if (loopCount == 0) {
                                loopCount = extendedValue;
                            }
                            else {
                                loopCount--;
                            }
                            
                            if (loopCount > 0) {
                                startRow = loopRowStack.peek();
                                startPattern = orderTableIndex;
                                breakPattern = true;
                            }
                            else {
                                loopRowStack.pop();
                                loopCount = loopCountStack.pop();
                            }
                        }
                    }
                }
            }

            case 0xf -> { // set speed / BPM
                if (note.effectParameters < 32) { // set speed (ticks per row)
                    speed = note.effectParameters;
                }
                else { // set BPM
                    BPM = note.effectParameters;
                    ticksPerSecond = (2 * BPM) / 5.0;
                    samplesPerTick = (int) (DATA_LINE_SAMPLE_RATE * (1.0 / ticksPerSecond));
                }
            }
        }        
    }

    private boolean breakPattern = false;
    private int startPattern = 0;
    private int startRow = 0;
    private int loopCount = 0;
    private int lastJumpToPatternRow = -1;

    public byte[] generatePCM() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        breakPattern = false;
        startPattern = 0;
        startRow = 0;
        loopCount = 0;
        lastJumpToPatternRow = -1;

        boolean playing = true;

        pattern_loop:
        while (playing) {
            for (int orderTableIndex = startPattern; orderTableIndex < songLength; orderTableIndex++) {
                if (!breakPattern) startPattern = 0;
                int patternIndex = patternOrderTable[orderTableIndex];

                for (int currentRow = startRow; currentRow < 64; currentRow++) {
                    if (breakPattern) {
                        breakPattern = false;
                        continue pattern_loop;
                    }
                    else {
                        startRow = 0;
                    }
                    
                    for (int tick = 0; tick < speed; tick++) {
                        for (int ch = 0; ch < channelsNum; ch++) {
                            Channel channel = channels[ch];
                            PatternNote note = notes[patternIndex][currentRow][ch];

                            if (tick == 0) {
                                channel.triggerNote(this, note);

                                if (note.effectNumber != 0 || note.effectParameters != 0) {
                                    channel.startEffect(note);
                                    startEffect(note, orderTableIndex, currentRow);
                                }
                            }
                            else {
                                channel.updateEffect(tick, note);
                            }
                        }

                        // mix all channels
                        for (int s = 0; s < samplesPerTick; s++) {
                            byte mixedSample = 0;
                            for (int ch = 0; ch < channelsNum; ch++) {
                                int ms = mixedSample + channels[ch].getNextSample() / 2;
                                mixedSample = (byte) Math.max(Math.min(ms, 127), -128);
                            }
                            baos.write(mixedSample);
                        }
                    }
                }
            }
            playing = false;
        }
        
        return baos.toByteArray();
    }

}
