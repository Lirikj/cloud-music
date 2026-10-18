import SwiftUI
import CloudMusicCore

struct Palette {
    let dark:Bool
    var bg:Color{dark ? Color(hex:0x111416):Color(hex:0xf7f8f5)}
    var sidebar:Color{dark ? Color(hex:0x15191b):Color(hex:0xebeFE9)}
    var panel:Color{dark ? Color(hex:0x1a1f21):Color(hex:0xffffff)}
    var top:Color{dark ? Color(hex:0x0b0e10):Color(hex:0xeef1eb)}
    var raised:Color{dark ? Color(hex:0x252c2e):Color(hex:0xe8ede5)}
    var text:Color{dark ? Color(hex:0xf2f5ef):Color(hex:0x1b241e)}
    var muted:Color{dark ? Color(hex:0x899590):Color(hex:0x748076)}
    var green:Color{dark ? Color(hex:0x63e68c):Color(hex:0x187442)}
    var onGreen:Color{dark ? Color(hex:0x10291a):.white}
    var line:Color{dark ? Color.white.opacity(0.07):Color.black.opacity(0.07)}
    var selected:Color{dark ? Color(hex:0x263b30):Color(hex:0xdcebdc)}
}
private struct PaletteKey:EnvironmentKey{static let defaultValue=Palette(dark:true)}
extension EnvironmentValues{var cm:Palette{get{self[PaletteKey.self]}set{self[PaletteKey.self]=newValue}}}
extension Color{init(hex:UInt32){self.init(red:Double((hex>>16)&255)/255,green:Double((hex>>8)&255)/255,blue:Double(hex&255)/255)}}

enum CMMotion {
    static let quick=Animation.easeOut(duration:0.16)
    static let page=Animation.easeInOut(duration:0.24)
    static let spring=Animation.interactiveSpring(response:0.32,dampingFraction:0.86)
}
struct SoftPressStyle:ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration:Configuration)->some View {
        configuration.label.opacity(configuration.isPressed ? 0.78:1)
            .scaleEffect(reduceMotion ? 1:configuration.isPressed ? 0.975:1)
            .animation(reduceMotion ? nil:CMMotion.quick,value:configuration.isPressed)
    }
}
struct IconPressStyle:ButtonStyle {
    @Environment(\.cm) private var p
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovered=false
    func makeBody(configuration:Configuration)->some View {
        configuration.label.background(hovered ? p.raised:.clear,in:RoundedRectangle(cornerRadius:10))
            .scaleEffect(reduceMotion ? 1:configuration.isPressed ? 0.88:1)
            .opacity(configuration.isPressed ? 0.7:1).onHover{hovered=$0}
            .animation(reduceMotion ? nil:CMMotion.quick,value:hovered)
            .animation(reduceMotion ? nil:CMMotion.quick,value:configuration.isPressed)
    }
}
struct Brand:View {
    @Environment(\.cm) private var p
    var body:some View {
        HStack(spacing:10) {
            HStack(spacing:3){Capsule().frame(width:4,height:15);Capsule().frame(width:4,height:25);Capsule().frame(width:4,height:19)}
                .foregroundStyle(p.green).frame(width:30,height:34).background(p.green.opacity(0.08),in:RoundedRectangle(cornerRadius:10))
            Text("cloud music").font(.system(size:20,weight:.semibold)).tracking(-0.7)
        }.foregroundStyle(p.text)
    }
}
struct Tool:View {
    @Environment(\.cm) private var p
    let icon:String;let label:String;var active=false;let action:()->Void
    var body:some View {
        Button(action:action){Image(systemName:icon).font(.system(size:15,weight:.medium)).frame(width:34,height:34).contentShape(RoundedRectangle(cornerRadius:10))}
            .buttonStyle(IconPressStyle()).foregroundStyle(active ? p.green:p.muted).help(label).accessibilityLabel(label)
    }
}
struct PrimaryButton:View {
    @Environment(\.cm) private var p
    let title:String;var icon="play.fill";let action:()->Void
    var body:some View {
        Button(action:action){Label(title,systemImage:icon).font(.system(size:13,weight:.semibold)).padding(.horizontal,21).padding(.vertical,13).background(p.green,in:Capsule()).foregroundStyle(p.onGreen)}
            .buttonStyle(SoftPressStyle())
    }
}
struct SourceBadge:View {
    @Environment(\.cm) private var p
    let title:String;var icon="cloud"
    var body:some View{Label(title,systemImage:icon).font(.system(size:10,weight:.medium)).foregroundStyle(p.muted).padding(.horizontal,10).padding(.vertical,6).background(p.raised.opacity(0.55),in:Capsule())}
}
struct FallbackCover:View {
    let track:Track?;var size:CGFloat
    private var seed:Int{Int((track?.id ?? "0").prefix(2),radix:16) ?? 0}
    private let colors:[(UInt32,UInt32,UInt32)]=[(0x123b30,0x678d6d,0xe5d7a7),(0x233e4b,0x91acb2,0xe4c4b0),(0x5e3932,0xc58b6a,0xefc9a5),(0x39446b,0x9494b8,0xe6c5d8)]
    var body:some View {
        let c=colors[seed%colors.count]
        ZStack(alignment:.topLeading) {
            LinearGradient(colors:[Color(hex:c.1),Color(hex:c.0)],startPoint:.topLeading,endPoint:.bottomTrailing)
            Circle().fill(Color(hex:c.2).opacity(0.8)).frame(width:size*0.85,height:size*0.85).offset(x:size*0.4,y:size*0.34)
            Ellipse().fill(Color(hex:c.0)).frame(width:size*1.7,height:size*0.8).rotationEffect(.degrees(-20)).offset(x:-size*0.25,y:size*0.77)
            if size>70 {
                VStack(alignment:.leading) {
                    Image(systemName:"waveform").font(.system(size:size*0.14,weight:.light))
                    Spacer()
                    Text(track?.artist.uppercased() ?? "CLOUD MUSIC").font(.system(size:max(9,size*0.046),weight:.medium)).tracking(size*0.012).lineLimit(2)
                }.padding(size*0.12).foregroundStyle(.white.opacity(0.85))
            }
        }.frame(width:size,height:size,alignment:.topLeading).clipped().accessibilityHidden(true)
    }
}
struct Cover:View {
    @EnvironmentObject private var model:LibraryModel
    @EnvironmentObject private var artwork:ArtworkStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let track:Track?;var size:CGFloat;var radius:CGFloat=12
    @State private var resolvedImage:NSImage?
    @State private var resolvedID:String?
    @State private var epoch = -1
    private var taskKey:String{"\(track?.id ?? ""):\(artwork.revision):\(artwork.cacheEpoch):\(artwork.audioPriority)"}
    var body:some View {
        ZStack {
            FallbackCover(track:track,size:size)
            if resolvedID==track?.id,let image=resolvedImage {
                Image(nsImage:image).resizable().aspectRatio(contentMode:.fill).frame(width:size,height:size).clipped().transition(.opacity)
            }
        }.frame(width:size,height:size).clipShape(RoundedRectangle(cornerRadius:radius,style:.continuous))
            .overlay(RoundedRectangle(cornerRadius:radius,style:.continuous).strokeBorder(.white.opacity(0.06),lineWidth:0.5))
            .task(id:taskKey) {
                guard let track else{resolvedImage=nil;resolvedID=nil;return}
                if resolvedID != track.id || epoch != artwork.cacheEpoch{resolvedImage=nil;resolvedID=track.id;epoch=artwork.cacheEpoch}
                guard resolvedImage==nil else{return}
                if let image=artwork.image(track){withAnimation(reduceMotion ? nil:.easeOut(duration:0.2)){resolvedImage=image}}
                else{artwork.request(track,source:model.source(track),localURL:model.localURL(track))}
            }.onDisappear{resolvedImage=nil;resolvedID=nil}
    }
}
struct NowPlayingGlyph:View {
    @Environment(\.cm) private var p
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var moving=false
    var body:some View {
        HStack(alignment:.center,spacing:2) {
            ForEach(0..<3){i in Capsule().fill(p.green).frame(width:3,height:reduceMotion ? 10:moving ? CGFloat([15,7,12][i]):CGFloat([5,14,6][i]))
                .animation(reduceMotion ? nil:.easeInOut(duration:0.65).repeatForever(autoreverses:true).delay(Double(i)*0.11),value:moving)}
        }.frame(width:18,height:18).onAppear{moving=true}.accessibilityHidden(true)
    }
}
struct TrackActions:View {
    @EnvironmentObject private var model:LibraryModel
    let track:Track
    var body:some View {
        Button("Включить"){model.play(track)}
        Button(model.library.favorites.contains(track.id) ? "Убрать из избранного":"Добавить в избранное"){model.toggleFavorite(track)}
        Button("К исполнителю"){model.openArtist(track.artist)}
        Divider()
        if model.downloaded(track){Button("Показать в Finder"){model.reveal(track)};Button("Удалить скачанную копию",role:.destructive){model.removeDownload(track)}}
        else if track.provider == .yandex || track.provider == .google {
            if model.downloadStates[track.id]?.active==true{Button("Отменить скачивание"){model.cancelDownload(track)}}
            else{Button("Скачать на Mac\(track.size>0 ? " · "+ByteCountFormatter.string(fromByteCount:track.size,countStyle:.file):"")"){model.download(track)}}
        } else{Button("Показать в Finder"){model.reveal(track)}}
    }
}
struct TrackRow:View {
    @EnvironmentObject private var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let track:Track;let number:Int
    @State private var hovered=false
    private var current:Bool{player.current?.id==track.id}
    var body:some View {
        HStack(spacing:10) {
            Button{model.play(track)}label:{
                HStack(spacing:14) {
                    Group {
                        if current && player.isPlaying{NowPlayingGlyph()}
                        else if hovered{Image(systemName:"play.fill").foregroundStyle(current ? p.green:p.text)}
                        else{Text("\(number)").foregroundStyle(current ? p.green:p.muted)}
                    }.font(.system(size:11,weight:.medium)).frame(width:22)
                    Cover(track:track,size:42,radius:8)
                    VStack(alignment:.leading,spacing:5) {
                        Text(track.title).font(.system(size:13,weight:.medium)).foregroundStyle(current ? p.green:p.text).lineLimit(1)
                        Text(track.artist).font(.system(size:11)).foregroundStyle(p.muted).lineLimit(1)
                    }
                    Spacer(minLength:8)
                    if model.downloaded(track){Image(systemName:"checkmark.circle.fill").font(.system(size:12)).foregroundStyle(p.green).help("Доступно офлайн")}
                    Text(track.duration>0 ? formatTime(track.duration):"—").font(.system(size:11)).foregroundStyle(p.muted).monospacedDigit().frame(width:38,alignment:.trailing)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Включить \(track.title) — \(track.artist)")
            Tool(icon:model.library.favorites.contains(track.id) ? "heart.fill":"heart",label:"Избранное \(track.title)",active:model.library.favorites.contains(track.id)){model.toggleFavorite(track)}
                .opacity(hovered || model.library.favorites.contains(track.id) ? 1:0.45)
            Menu{TrackActions(track:track)}label:{Image(systemName:"ellipsis").font(.system(size:14)).frame(width:22,height:30).foregroundStyle(p.muted)}
                .menuStyle(.borderlessButton).fixedSize().accessibilityLabel("Действия с треком \(track.title)")
        }.padding(.horizontal,10).padding(.vertical,7)
            .background(current ? p.selected.opacity(0.6):hovered ? p.raised.opacity(0.7):.clear,in:RoundedRectangle(cornerRadius:12))
            .onHover{hovered=$0}.animation(reduceMotion ? nil:CMMotion.quick,value:hovered).contextMenu{TrackActions(track:track)}
    }
}
func formatTime(_ seconds:Double)->String{guard seconds.isFinite,seconds>=0 else{return "0:00"};let n=Int(seconds);return "\(n/60):\(String(format:"%02d",n%60))"}
func counted(_ number:Int,_ one:String,_ few:String,_ many:String)->String{let n=abs(number),ending=(11...14).contains(n%100) ? many:n%10==1 ? one:(2...4).contains(n%10) ? few:many;return "\(number) \(ending)"}
