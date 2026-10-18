from pathlib import Path
import math, struct, wave, sys, unicodedata
root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
for name,freq in [('По орбите',220),('Тихий свет',261.63),('Новый день',329.63)]:
    rate=22050; seconds=18
    # HFS+ installer images normalize names; sign the same spelling on APFS.
    filename=unicodedata.normalize('NFD',f'Cloud Music - {name}.wav')
    with wave.open(str(root/filename),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate)
        for start in range(0,rate*seconds,rate):
            values=[]
            for n in range(start,min(start+rate,rate*seconds)):
                t=n/rate;env=min(t,1,max(0,seconds-t));v=0.09*env*(math.sin(2*math.pi*freq*t)+0.3*math.sin(2*math.pi*freq*1.5*t))*0.6
                values.append(struct.pack('<h',round(v*32767)))
            out.writeframesraw(b''.join(values))
