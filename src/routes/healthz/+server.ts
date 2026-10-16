export const prerender = false;

export function GET() {
	return new Response('ok\n', { headers: { 'content-type': 'text/plain' } });
}
