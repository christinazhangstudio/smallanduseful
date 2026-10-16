import { error, json } from '@sveltejs/kit';
import { listSessions } from '$lib/server/translation-sessions';
import type { RequestEvent } from './$types';

export const prerender = false;

export async function GET({ url }: RequestEvent) {
	const raw = url.searchParams.get('page') ?? '0';
	if (!/^\d{1,6}$/.test(raw)) error(400, 'page must be a nonnegative integer of at most six digits.');
	return json(await listSessions(Number(raw)), { headers: { 'cache-control': 'no-store' } });
}
