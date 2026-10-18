import Foundation

public enum ArtworkParser {
    private static func number(_ bytes:ArraySlice<UInt8>,sync:Bool=false)->Int? {
        var n=0;for b in bytes {if sync && b>127{return nil};n=(n << (sync ? 7 : 8)) | Int(b)};return n
    }
    public static func requiredPrefix(_ data:Data)->Int? {
        let b=[UInt8](data),limit=4*1024*1024
        guard b.count>=10 else{return nil}
        if Array(b.prefix(3))==[73,68,51],let n=number(b[6..<10],sync:true),n+10<=limit{return n+10>b.count ? n+10:nil}
        if Array(b.prefix(4))==[102,76,97,67] {
            var p=4
            while p+4<=b.count {
                let last=b[p]&0x80 != 0,n=number(b[p+1..<p+4]) ?? 0,end=p+4+n
                guard end<=limit else{return nil}
                if end>b.count{return end}
                if last{return nil};p=end
            }
            return p+4<=limit ? p+4:nil
        }
        return nil
    }
    public static func image(in data:Data)->Data? {
        let b=[UInt8](data);guard b.count>=10 else{return nil}
        if Array(b.prefix(3))==[73,68,51] {
            let version=b[3];guard (2...4).contains(version),let size=number(b[6..<10],sync:true),size<=4*1024*1024 else{return nil}
            let end=min(b.count,size+10);var p=10
            if b[5]&0x40 != 0,version>=3,p+4<=end,let ext=number(b[p..<p+4],sync:version==4){p += version==4 ? ext : ext+4}
            while p+(version==2 ? 6:10)<=end {
                let start=p,header=version==2 ? 6:10,keyLength=version==2 ? 3:4
                let key=String(bytes:b[p..<p+keyLength],encoding:.ascii) ?? ""
                guard !key.isEmpty,b[p] != 0,let length=number(b[p+keyLength..<p+header-(version==2 ? 0:2)],sync:version==4),length>0,length<=4*1024*1024,p+header+length<=end else{break}
                p += header
                if key=="APIC" || key=="PIC" {
                    var payload=Array(b[p..<p+length]);if b[5]&0x80 != 0 || version==4 && b[start+9]&0x02 != 0 {var clean:[UInt8]=[];var j=0;while j<payload.count{clean.append(payload[j]);if payload[j]==255,j+1<payload.count,payload[j+1]==0{j+=1};j+=1};payload=clean}
                    if let img=apic(payload,v2:key=="PIC"){return img}
                }
                p += length
            }
        } else if Array(b.prefix(4))==[102,76,97,67] {
            var p=4
            while p+4<=b.count {
                let last=b[p]&0x80 != 0,type=b[p]&0x7f,n=number(b[p+1..<p+4]) ?? 0;p+=4
                guard n<=4*1024*1024,p+n<=b.count else{return nil}
                if type==6,let image=flac(Array(b[p..<p+n])){return image};p+=n;if last{break}
            }
        }
        return nil
    }
    private static func apic(_ b:[UInt8],v2:Bool)->Data? {
        guard b.count>5 else{return nil};let enc=b[0];var p=1
        if v2{p+=3}else{guard let zero=b[p...].firstIndex(of:0) else{return nil};p=zero+1}
        p+=1;guard p<b.count else{return nil}
        if enc==1 || enc==2 {var found=false;while p+1<b.count{if b[p]==0 && b[p+1]==0{p+=2;found=true;break};p+=2};if !found{return nil}}
        else{guard let z=b[p...].firstIndex(of:0) else{return nil};p=z+1}
        guard p<b.count else{return nil};return Data(b[p...])
    }
    private static func flac(_ b:[UInt8])->Data? {
        var p=4
        func advanceLength()->Bool{guard p+4<=b.count,let n=number(b[p..<p+4]),n<=b.count-p-4 else{return false};p+=4+n;return true}
        guard advanceLength(),advanceLength() else{return nil};p+=16
        guard p+4<=b.count,let n=number(b[p..<p+4]),n>0,n<=b.count-p-4 else{return nil};return Data(b[p+4..<p+4+n])
    }
}
