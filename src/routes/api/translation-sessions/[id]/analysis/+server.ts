import { json } from '@sveltejs/kit';
import { analyseSession, readSessionJSON, sessionId } from '$lib/server/translation-sessions';
import type { RequestEvent } from './$types';

export const prerender = false;

export async function POST(event: RequestEvent) {
	const id = sessionId(event);
	const value = await readSessionJSON(event, 16384);
	return json({ analysis: await analyseSession(id, value) });
}
