package main

import (
 "context"
 "encoding/binary"
 "encoding/json"
 "errors"
 "io"
 "net/http"
 "net/url"
 "sort"
 "strconv"
 "strings"
 "time"
)

var errUnsupported = errors.New("unsupported media")

type format struct {
 URL string `json:"url"`
 ID string `json:"format_id"`
 Ext string `json:"ext"`
 Protocol string `json:"protocol"`
 VCodec string `json:"vcodec"`
 ACodec string `json:"acodec"`
 DRM bool `json:"has_drm"`
 Height int `json:"height"`
 Width int `json:"width"`
 FPS float64 `json:"fps"`
 TBR float64 `json:"tbr"`
 VBR float64 `json:"vbr"`
 ABR float64 `json:"abr"`
 Size int64 `json:"filesize"`
 Language string `json:"language"`
 LanguagePreference float64 `json:"language_preference"`
}
type extraction struct {
 Duration float64 `json:"duration"`
 Live bool `json:"is_live"`
 LiveStatus string `json:"live_status"`
 Formats []format `json:"formats"`
}
type ranges struct {
 InitStart int `json:"initStart"`
 InitEnd int `json:"initEnd"`
 IndexStart int `json:"indexStart"`
 IndexEnd int `json:"indexEnd"`
}
type stream struct {
 ranges
 URL string `json:"url"`
 Itag int `json:"itag"`
 Format string `json:"format"`
 Mime string `json:"mimeType"`
 Codec string `json:"codec"`
 VideoOnly bool `json:"videoOnly"`
 Bitrate int `json:"bitrate"`
 Quality string `json:"quality"`
 Width int `json:"width"`
 Height int `json:"height"`
 FPS int `json:"fps"`
 Duration int64 `json:"durationMs"`
 Size int64 `json:"contentLength"`
 AudioID string `json:"audioTrackId,omitempty"`
 AudioType string `json:"audioTrackType,omitempty"`
 AudioLocale string `json:"audioTrackLocale,omitempty"`
 AudioName string `json:"audioTrackName,omitempty"`
}

func googleMedia(raw string) (*url.URL,error) {
 u,e:=url.Parse(raw)
 if e!=nil || u.Scheme!="https" || !strings.HasSuffix(u.Hostname(),".googlevideo.com") || u.User!=nil || u.Port()!="" || u.Path!="/videoplayback" { return nil,errUnsupported }
 return u,nil
}
func proxyURL(raw,base string)(string,error) {
 u,e:=googleMedia(raw);if e!=nil{return "",e}
 b,e:=url.Parse(base);if e!=nil || (b.Scheme!="https"&&b.Scheme!="http") || b.Host=="" || b.User!=nil || (b.Path!=""&&b.Path!="/") || b.RawQuery!="" || b.Fragment!="" {return "",errUnsupported}
 q:=u.Query();q.Set("host",u.Hostname());b.Path=u.Path;b.RawQuery=q.Encode();return b.String(),nil
}
func mp4Ranges(b []byte)(ranges,error) {
 ftyp,moov:=false,false
 for pos:=0;pos+8<=len(b); {
  size:=uint64(binary.BigEndian.Uint32(b[pos:]));header:=8;kind:=string(b[pos+4:pos+8])
  if size==1 {if pos+16>len(b){break};size=binary.BigEndian.Uint64(b[pos+8:]);header=16}
  if size<uint64(header){return ranges{},errUnsupported};if size>uint64(len(b)-pos){break}
  end:=pos+int(size)
  if kind=="ftyp"&&pos==0 {ftyp=true};if kind=="moov"{moov=true}
  if kind=="sidx"&&ftyp&&moov {
   if int(size)<header+24{return ranges{},errUnsupported};v:=b[pos+header];if v>1{return ranges{},errUnsupported}
   at:=pos+header+22;if v==1{at+=8}
   if at+2>end||binary.BigEndian.Uint32(b[pos+header+8:])==0{return ranges{},errUnsupported}
   count:=int(binary.BigEndian.Uint16(b[at:]));if count==0||at+2+count*12!=end{return ranges{},errUnsupported}
   for i:=0;i<count;i++{if binary.BigEndian.Uint32(b[at+2+i*12:])>>31!=0{return ranges{},errUnsupported}}
   return ranges{0,pos-1,pos,end-1},nil
  }
  pos=end
 }
 return ranges{},errUnsupported
}
func selectFormats(x extraction,max int)([]format,[]format,error) {
 if x.Duration<=0||x.Live||x.LiveStatus=="is_live" {return nil,nil,errUnsupported}
 var videos,audios []format
 for _,f:=range x.Formats {
  if f.URL==""||f.DRM||(f.Protocol!="https"&&f.Protocol!="http"){continue}
  if f.Ext=="mp4"&&strings.HasPrefix(f.VCodec,"avc1.")&&f.ACodec=="none"&&f.Height>0&&f.Height<=max&&f.Width>0&&f.FPS>0 {videos=append(videos,f)}
  if (f.Ext=="m4a"||f.Ext=="mp4")&&f.VCodec=="none"&&strings.HasPrefix(f.ACodec,"mp4a.")&&!strings.Contains(f.ID,"drc"){audios=append(audios,f)}
 }
 sort.SliceStable(videos,func(i,j int)bool{return videos[i].TBR>videos[j].TBR});seen:=map[string]bool{};unique:=videos[:0]
 for _,f:=range videos{k:=strconv.Itoa(f.Height)+":"+strconv.FormatFloat(f.FPS,'f',-1,64);if !seen[k]{seen[k]=true;unique=append(unique,f)}}
 videos=unique;sort.SliceStable(videos,func(i,j int)bool{return videos[i].Height>videos[j].Height});if len(videos)>8{videos=videos[:8]}
 sort.SliceStable(audios,func(i,j int)bool{if audios[i].LanguagePreference!=audios[j].LanguagePreference{return audios[i].LanguagePreference>audios[j].LanguagePreference};return audios[i].ABR>audios[j].ABR});if len(audios)>1{audios=audios[:1]}
 return videos,audios,nil
}
func metadata(f format,x extraction,r ranges,base string,audio bool)(stream,error) {
 u,e:=googleMedia(f.URL);if e!=nil{return stream{},e};tag:=u.Query().Get("itag");if tag==""{tag=strings.Split(f.ID,"-")[0]};itag,e:=strconv.Atoi(tag);if e!=nil||itag<=0{return stream{},errUnsupported}
 rate:=f.VBR;if rate<=0{rate=f.TBR};if audio {rate=f.ABR;if rate<=0{rate=f.TBR}};if rate<=0||rate>2147483{return stream{},errUnsupported}
 proxy,e:=proxyURL(f.URL,base);if e!=nil{return stream{},e};s:=stream{ranges:r,URL:proxy,Itag:itag,Format:"MPEG_4",Mime:"video/mp4",Codec:f.VCodec,VideoOnly:!audio,Bitrate:int(rate*1000+.5),Quality:strconv.Itoa(f.Height)+"p",Width:f.Width,Height:f.Height,FPS:int(f.FPS+.5),Duration:int64(x.Duration*1000+.5),Size:f.Size}
 if s.Size<=0{s.Size=-1};if f.FPS>30{s.Quality+=strconv.Itoa(s.FPS)}
 if audio{s.Format="M4A";s.Mime="audio/mp4";s.Codec=f.ACodec;s.Quality=strconv.Itoa(int(rate+.5))+" kbps";s.Width=0;s.Height=0;s.FPS=0;s.AudioID=f.Language;if s.AudioID==""{s.AudioID="original"};s.AudioLocale=f.Language;s.AudioName=f.Language;if s.AudioName==""{s.AudioName="Original"};s.AudioType="original"}
 return s,nil
}

var mediaClient=&http.Client{Timeout:10*time.Second,Transport:&http.Transport{DisableCompression:true,MaxConnsPerHost:2},CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}
func readRange(ctx context.Context,raw,host string,start,end int64)([]byte,error) {
 req,e:=http.NewRequestWithContext(ctx,"GET",raw,nil);if e!=nil{return nil,e};if host!=""{req.Host=host};req.Header.Set("Range","bytes="+strconv.FormatInt(start,10)+"-"+strconv.FormatInt(end,10));req.Header.Set("Accept-Encoding","identity")
 res,e:=mediaClient.Do(req);if e!=nil{return nil,errUnsupported};defer res.Body.Close()
 typ:=res.Header.Get("Content-Type");if res.StatusCode!=206||(!strings.Contains(typ,"/mp4")&&!strings.Contains(typ,"/octet-stream")){return nil,errUnsupported}
 header:=res.Header.Get("Content-Range");parts:=strings.Split(strings.TrimPrefix(header,"bytes "),"-");if !strings.HasPrefix(header,"bytes ")||len(parts)!=2{return nil,errUnsupported};n,e:=strconv.ParseInt(parts[0],10,64);if e!=nil||n!=start{return nil,errUnsupported}
 b,e:=io.ReadAll(io.LimitReader(res.Body,end-start+2));if e!=nil||int64(len(b))>end-start+1{return nil,errUnsupported};return b,nil
}
func rawJSON(v any)json.RawMessage{b,_:=json.Marshal(v);return b}
