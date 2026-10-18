import SwiftUI
import CloudMusicCore

struct CollectionView:View {
    @EnvironmentObject private var model:LibraryModel
    @ObservedObject var player:PlayerModel
    @Environment(\.cm) private var p
    var body:some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment:.leading,spacing:28) {
                    header.id("top")
                    if model.selected=="artists" && model.artist==nil && model.albumID==nil && model.query.isEmpty{artistGrid}
                    else if model.selected=="local" && model.artist==nil && model.albumID==nil && model.query.isEmpty{localFolders;trackList}
                    else if model.selected=="downloads"{downloadJobs;if model.visibleTracks.isEmpty && !model.downloadStates.values.contains(where:{$0.active}){emptyState}else{trackList}}
                    else if model.visibleTracks.isEmpty{emptyState}
                    else if model.grid && model.selected=="library" && model.artist==nil && model.albumID==nil && model.query.isEmpty{featured;albumGrid;trackList}
                    else{trackList}
                }.padding(30).frame(maxWidth:1160,alignment:.leading).frame(maxWidth:.infinity)
            }.onChange(of:model.query){_ in proxy.scrollTo("top",anchor:.top)}
        }.background(p.bg)
    }
    private var header:some View {
        HStack(alignment:.center) {
            VStack(alignment:.leading,spacing:8) {
                Text(model.pageTitle).font(.system(size:30,weight:.semibold)).tracking(-1).foregroundStyle(p.text)
                Text(subtitle).font(.system(size:12)).foregroundStyle(p.muted)
            }
            Spacer()
            HStack(spacing:2) {
                Tool(icon:"square.grid.2x2",label:"Обложки",active:model.grid){withAnimation(CMMotion.page){model.grid=true}}
                Tool(icon:"list.bullet",label:"Список",active:!model.grid){withAnimation(CMMotion.page){model.grid=false}}
            }.padding(3).background(p.panel,in:Capsule()).overlay(Capsule().strokeBorder(p.line,lineWidth:0.5))
        }
    }
    private var subtitle:String {
        if model.artist != nil{return "Исполнитель · "+counted(model.visibleTracks.count,"трек","трека","треков")}
        if model.albumID != nil{return "Коллекция · "+counted(model.visibleTracks.count,"трек","трека","треков")}
        if model.selected=="local"{return "Локальные папки и музыка, сохранённая тобой"}
        if model.selected=="downloads"{return "Твоя музыка, доступная без интернета"}
        if model.selected.hasPrefix("source:"),let source=model.library.sources.first(where:{"source:"+$0.id==model.selected}){return counted(model.visibleTracks.count,"трек","трека","треков")+" · "+source.provider.label}
        return counted(model.visibleTracks.count,"трек","трека","треков")+" · "+counted(model.library.sources.count,"источник","источника","источников")
    }
    @ViewBuilder private var featured:some View {
        if let album=model.albums.first {
            HStack(spacing:25) {
                Cover(track:album.first,size:148,radius:14).shadow(color:.black.opacity(p.dark ? 0.25:0.09),radius:14,y:8)
                VStack(alignment:.leading,spacing:11) {
                    Text("ИЗ ТВОЕЙ КОЛЛЕКЦИИ").font(.system(size:9,weight:.semibold)).tracking(2).foregroundStyle(p.muted)
                    Text(model.albumTitle(album)).font(.system(size:29,weight:.semibold)).tracking(-0.8).lineLimit(2).foregroundStyle(p.text)
                    Text("\(model.albumTitle(album)==album.artist ? album.name:album.artist) · \(counted(album.tracks.count,"трек","трека","треков"))").font(.system(size:12)).foregroundStyle(p.muted).lineLimit(1)
                    HStack(spacing:12) {
                        PrimaryButton(title:"Слушать"){player.play(album.first,in:album.tracks)}
                        Tool(icon:"arrow.up.right",label:"Открыть альбом"){withAnimation(CMMotion.page){model.openAlbum(album)}}
                    }.padding(.top,4)
                }
                Spacer(minLength:0)
            }.padding(24).frame(maxWidth:.infinity,alignment:.leading)
                .background(LinearGradient(colors:[p.selected.opacity(0.8),p.panel],startPoint:.topLeading,endPoint:.bottomTrailing),in:RoundedRectangle(cornerRadius:22))
                .overlay(RoundedRectangle(cornerRadius:22).strokeBorder(p.line,lineWidth:0.5))
        }
    }
    private var albumGrid:some View {
        VStack(alignment:.leading,spacing:17) {
            HStack {
                Text("Твои коллекции").font(.system(size:18,weight:.semibold)).foregroundStyle(p.text)
                Spacer()
                Button("Все треки ↗"){withAnimation(CMMotion.page){model.grid=false}}.buttonStyle(SoftPressStyle()).font(.system(size:11,weight:.medium)).foregroundStyle(p.muted)
            }
            LazyVGrid(columns:[GridItem(.adaptive(minimum:154,maximum:190),spacing:16)],alignment:.leading,spacing:19) {
                ForEach(Array(model.albums.prefix(8))){album in AlbumCard(album:album,player:player)}
            }
        }
    }
    private var artistGrid:some View {
        LazyVGrid(columns:[GridItem(.adaptive(minimum:145,maximum:190),spacing:20)],alignment:.leading,spacing:24) {
            ForEach(model.artists){artist in ArtistCard(artist:artist)}
        }
    }
    private var localFolders:some View {
        VStack(alignment:.leading,spacing:18) {
            HStack(spacing:14) {
                FolderCard(title:"Скачанные треки",symbol:"arrow.down.circle",subtitle:counted(model.library.downloads.count,"файл","файла","файлов")+" · без сети"){model.navigate("downloads")}
                FolderCard(title:"Добавить с Mac",symbol:"folder.badge.plus",subtitle:"Папки и отдельные файлы"){model.chooseLocal()}
            }
            let sources=model.library.sources.filter{$0.provider == .local || $0.provider == .demo}
            if !sources.isEmpty {
                Text("Папки на Mac").font(.system(size:16,weight:.semibold)).foregroundStyle(p.text).padding(.top,8)
                LazyVGrid(columns:[GridItem(.adaptive(minimum:190),spacing:14)],spacing:14) {
                    ForEach(sources){source in
                        FolderCard(title:source.name,symbol:"folder",subtitle:counted(model.library.tracks.filter{$0.sourceID==source.id}.count,"трек","трека","треков")){model.navigate("source:"+source.id)}
                            .contextMenu{Button("Обновить"){model.refresh(source)};Button("Отключить папку",role:.destructive){model.remove(source)}}
                    }
                }
            }
        }
    }
    private var downloadJobs:some View {
        VStack(alignment:.leading,spacing:12) {
            ForEach(model.tracks.filter{model.downloadStates[$0.id] != nil && !model.downloaded($0)}){track in
                if let state=model.downloadStates[track.id] {
                    HStack(spacing:14) {
                        Cover(track:track,size:46,radius:9)
                        VStack(alignment:.leading,spacing:6) {
                            Text(track.title).font(.system(size:13,weight:.medium)).foregroundStyle(p.text)
                            Text(state.label).font(.system(size:11)).foregroundStyle(p.muted).lineLimit(2)
                            if let fraction=state.fraction{ProgressView(value:fraction).tint(p.green)}
                        }
                        Spacer()
                        if state.active{Button("Отменить"){model.cancelDownload(track)}}else{Button("Повторить"){model.download(track)}}
                    }.padding(16).background(p.panel,in:RoundedRectangle(cornerRadius:15))
                }
            }
        }
    }
    private var trackList:some View {
        VStack(alignment:.leading,spacing:13) {
            HStack {
                Text(model.artist != nil ? "Треки исполнителя":"Треки").font(.system(size:18,weight:.semibold))
                Spacer()
                if !model.visibleTracks.isEmpty {
                    Button{if let track=model.visibleTracks.first{player.play(track,in:model.visibleTracks)}}label:{Label("Слушать всё",systemImage:"play.fill").font(.system(size:11,weight:.medium))}
                        .buttonStyle(SoftPressStyle()).foregroundStyle(p.green)
                }
            }.foregroundStyle(p.text).padding(.bottom,2)
            HStack{Text("#").frame(width:22);Text("НАЗВАНИЕ");Spacer();Image(systemName:"clock").padding(.trailing,85)}
                .font(.system(size:9,weight:.medium)).tracking(1.1).foregroundStyle(p.muted).padding(.horizontal,10).padding(.bottom,3)
            LazyVStack(spacing:3){ForEach(Array(model.visibleTracks.enumerated()),id:\.element.id){i,track in TrackRow(player:player,track:track,number:i+1)}}
        }
    }
    private var emptyState:some View {
        VStack(spacing:16) {
            Image(systemName:model.query.isEmpty ? "music.note.list":"magnifyingglass").font(.system(size:30,weight:.light)).foregroundStyle(p.muted).frame(width:74,height:74).background(p.panel,in:RoundedRectangle(cornerRadius:23))
            Text(model.query.isEmpty ? "Здесь пока тихо":"Ничего не найдено").font(.system(size:21,weight:.semibold)).foregroundStyle(p.text)
            Text(model.query.isEmpty ? "Добавь музыку или сохрани любимые треки.":"Попробуй другое название или имя исполнителя.").font(.system(size:12)).foregroundStyle(p.muted)
            if model.selected=="local" || model.selected=="downloads"{PrimaryButton(title:"Выбрать музыку",icon:"folder.badge.plus"){model.chooseLocal()}}
        }.frame(maxWidth:.infinity,minHeight:310)
    }
}
private struct AlbumCard:View {
    @EnvironmentObject private var model:LibraryModel
    @Environment(\.cm) private var p
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let album:AlbumGroup
    @ObservedObject var player:PlayerModel
    @State private var hovered=false
    var body:some View {
        ZStack(alignment:.topTrailing) {
            Button{withAnimation(CMMotion.page){model.openAlbum(album)}}label:{
                VStack(alignment:.leading,spacing:9) {
                    Cover(track:album.first,size:136,radius:12).frame(maxWidth:.infinity)
                    Text(model.albumTitle(album)).font(.system(size:12,weight:.semibold)).foregroundStyle(p.text).lineLimit(1)
                    Text(model.albumTitle(album)==album.artist ? album.name:album.artist).font(.system(size:11)).foregroundStyle(p.muted).lineLimit(1)
                }.padding(10).frame(maxWidth:.infinity,alignment:.leading).background(hovered ? p.raised:p.panel.opacity(0.65),in:RoundedRectangle(cornerRadius:17))
            }.buttonStyle(SoftPressStyle()).accessibilityLabel("Открыть коллекцию \(model.albumTitle(album))")
            Button{player.play(album.first,in:album.tracks)}label:{Image(systemName:"play.fill").font(.system(size:14,weight:.semibold)).foregroundStyle(p.onGreen).frame(width:40,height:40).background(p.green,in:Circle()).shadow(color:.black.opacity(0.2),radius:8,y:4)}
                .buttonStyle(SoftPressStyle()).padding(.top,102).padding(.trailing,13).opacity(hovered ? 1:0).accessibilityHidden(!hovered).help("Слушать коллекцию")
        }.onHover{hovered=$0}.offset(y:reduceMotion ? 0:hovered ? -3:0).animation(reduceMotion ? nil:CMMotion.quick,value:hovered)
    }
}
private struct ArtistCard:View {
    @EnvironmentObject private var model:LibraryModel
    @Environment(\.cm) private var p
    let artist:ArtistGroup
    var body:some View {
        Button{withAnimation(CMMotion.page){model.openArtist(artist.name)}}label:{
            VStack(spacing:11) {
                Cover(track:artist.first,size:116,radius:58)
                Text(artist.name).font(.system(size:13,weight:.medium)).foregroundStyle(p.text).lineLimit(1)
                Text(counted(artist.tracks.count,"трек","трека","треков")).font(.system(size:11)).foregroundStyle(p.muted)
            }.padding(14).frame(maxWidth:.infinity).background(p.panel.opacity(0.5),in:RoundedRectangle(cornerRadius:19))
        }.buttonStyle(SoftPressStyle()).accessibilityLabel("Исполнитель \(artist.name)")
    }
}
private struct FolderCard:View {
    @Environment(\.cm) private var p
    let title:String,symbol:String,subtitle:String;let action:()->Void
    var body:some View {
        Button(action:action){VStack(alignment:.leading,spacing:13) {
            Image(systemName:symbol).font(.system(size:23,weight:.light)).foregroundStyle(p.green).frame(width:46,height:46).background(p.green.opacity(0.09),in:RoundedRectangle(cornerRadius:14))
            Text(title).font(.system(size:14,weight:.semibold)).foregroundStyle(p.text)
            Text(subtitle).font(.system(size:11)).foregroundStyle(p.muted)
        }.padding(20).frame(maxWidth:.infinity,alignment:.leading).background(p.panel,in:RoundedRectangle(cornerRadius:18)).overlay(RoundedRectangle(cornerRadius:18).strokeBorder(p.line,lineWidth:0.5))}
            .buttonStyle(SoftPressStyle())
    }
}
struct WelcomeView:View {
    @EnvironmentObject private var model:LibraryModel
    @Environment(\.cm) private var p
    var body:some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment:.leading,spacing:24) {
                    ZStack {
                        RoundedRectangle(cornerRadius:27).fill(LinearGradient(colors:[p.selected,p.panel],startPoint:.topLeading,endPoint:.bottomTrailing)).frame(width:108,height:108)
                        Image(systemName:"cloud").font(.system(size:43,weight:.ultraLight)).foregroundStyle(p.green)
                        Image(systemName:"music.note").font(.system(size:20,weight:.medium)).foregroundStyle(p.green).offset(x:1,y:5)
                    }.padding(.bottom,6)
                    Text("Вся твоя музыка.\nВ одном месте.").font(.system(size:43,weight:.semibold)).tracking(-1.7).foregroundStyle(p.text)
                    Text("Подключи свою папку Яндекс Диска или Google Drive.\nА музыку с Mac можно слушать прямо с устройства.").font(.system(size:14)).lineSpacing(6).foregroundStyle(p.muted)
                    HStack(spacing:17) {
                        PrimaryButton(title:"Добавить ссылку",icon:"plus"){model.showConnect=true}
                        Button("Выбрать с Mac"){model.chooseLocal()}.buttonStyle(SoftPressStyle()).font(.system(size:12,weight:.medium)).foregroundStyle(p.text)
                    }.padding(.vertical,6)
                    HStack(spacing:9){SourceBadge(title:"Яндекс Диск");SourceBadge(title:"Google Drive",icon:"triangle");SourceBadge(title:"На этом Mac",icon:"laptopcomputer")}
                    Text("Только твои папки. Добавь первую, чтобы начать.").font(.system(size:11)).foregroundStyle(p.muted).padding(.top,10)
                }.padding(44).frame(maxWidth:780,minHeight:geo.size.height,alignment:.leading).frame(maxWidth:.infinity)
            }
        }.background(p.bg)
    }
}
