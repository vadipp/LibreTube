"""Bounded live extraction experiment; never emit signed URLs or credentials."""
import json
import sys
import urllib.request
import yt_dlp


class Quiet:
    def debug(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


def sample(fmt):
    try:
        headers = dict(fmt.get('http_headers') or {})
        headers['Range'] = 'bytes=0-65535'
        with urllib.request.urlopen(urllib.request.Request(fmt['url'], headers=headers), timeout=20) as response:
            body = response.read(65536)
            return {'status': response.status, 'bytes': len(body),
                    'content_type': response.headers.get('Content-Type', '')}
    except Exception as error:
        return {'error': type(error).__name__, 'status': getattr(error, 'code', None)}


for video_id in sys.argv[1:]:
    for client in ['default', 'android_vr']:
        options = {'quiet': True, 'no_warnings': True, 'logger': Quiet(),
                   'skip_download': True, 'socket_timeout': 20, 'retries': 1,
                   'extractor_retries': 1, 'noplaylist': True,
                   'js_runtimes': {'node': {}}, 'remote_components': {'ejs:github'}}
        if client != 'default':
            options['extractor_args'] = {'youtube': {'player_client': [client]}}
        try:
            with yt_dlp.YoutubeDL(options) as extractor:
                info = extractor.extract_info('https://www.youtube.com/watch?v=' + video_id, download=False)
            formats = [f for f in info.get('formats', []) if f.get('url') and f.get('vcodec') != 'images']
            videos = [f for f in formats if f.get('vcodec') not in (None, 'none')]
            audio = [f for f in formats if f.get('acodec') not in (None, 'none') and f.get('vcodec') == 'none']
            progressive = [f for f in videos if f.get('protocol') in ('http', 'https')]
            preferred = sorted(progressive, key=lambda f: (720 <= (f.get('height') or 0) <= 1080, f.get('height') or 0), reverse=True)
            report = {'extractor': 'yt-dlp', 'version': yt_dlp.version.__version__,
                      'id': video_id, 'client': client,
                      'heights': sorted(set(f.get('height') for f in videos if f.get('height'))),
                      'audio_tracks': len(audio), 'formats': len(formats)}
            if preferred:
                report['video_sample_height'] = preferred[0].get('height')
                report['video_sample'] = sample(preferred[0])
            if audio:
                report['audio_sample'] = sample(audio[0])
            print(json.dumps(report), flush=True)
        except Exception as error:
            message = str(error).lower()
            category = 'bot_check' if 'not a bot' in message else 'no_formats' if 'format' in message else 'extraction_error'
            print(json.dumps({'extractor': 'yt-dlp', 'id': video_id, 'client': client,
                              'error': type(error).__name__, 'category': category}), flush=True)
