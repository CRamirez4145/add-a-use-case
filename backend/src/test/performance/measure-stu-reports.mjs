import { writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import assert from 'node:assert/strict'

// Run only against a disposable, freshly seeded dev database: this deactivates seeded student 4.
// From backend/: node src/test/performance/measure-stu-reports.mjs
// STU_VERIFY_URL and STU_VERIFY_OUTPUT optionally override the local API and result file.
// Local diagnostic only: one instructor identity, no browser rendering or concurrent RAM editors.
const base = process.env.STU_VERIFY_URL || 'http://127.0.0.1/api/v1'
const output = process.env.STU_VERIFY_OUTPUT || 'target/stu-report-measurements.json'
const credentials = Buffer.from('b.gates@abc.edu:123456').toString('base64')
const login = await fetch(`${base}/users/login`, {
  method: 'POST',
  headers: { Authorization: `Basic ${credentials}` },
})
assert.equal(login.status, 200, 'seeded instructor login')
const {
  data: { token },
} = await login.json()
const studentLogin = await fetch(`${base}/users/login`, {
  method: 'POST',
  headers: { Authorization: `Basic ${Buffer.from('j.smith@abc.edu:123456').toString('base64')}` },
})
assert.equal(studentLogin.status, 200)
const {
  data: { token: studentToken },
} = await studentLogin.json()
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
async function request(path, method = 'GET', body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  })
  assert.equal(response.status, 200, `${method} ${path}`)
  return (await response.json()).data
}
const student = await request('/students/4')
assert.equal(student.enabled, true, 'fresh disposable seed')
const endpoints = [
  {
    name: 'section peer-evaluation report',
    path: `/evaluations/sections/${student.sectionId}/week/2023-W31`,
  },
  {
    name: 'student peer-evaluation period report',
    path: '/evaluations/students/4?startWeek=2023-W31&endWeek=2023-W35',
  },
  {
    name: 'student WAR search',
    path: '/activities/search?size=50',
    method: 'POST',
    body: { studentId: '4' },
  },
]
const flags = new Set(['enabled', 'studentEnabled', 'evaluateeEnabled', 'evaluatorEnabled'])
function history(value) {
  if (Array.isArray(value)) return value.map(history)
  if (value && typeof value === 'object')
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !flags.has(key))
        .map(([key, item]) => [key, history(item)]),
    )
  return value
}
const before = await Promise.all(
  endpoints.map((endpoint) => request(endpoint.path, endpoint.method, endpoint.body)),
)
const detailsBefore = await request('/evaluations/students/4/week/2023-W31/details')
const submittedBefore = await request('/evaluations/evaluators/4/week/2023-W31')
const ramBefore = await request(`/teams/${student.teamId}/documents/search`, 'POST', {})
const outcome = await request(`/sections/${student.sectionId}/students/4/deactivation`, 'POST')
assert.equal(outcome.outcome, 'DEACTIVATED')
const after = await Promise.all(
  endpoints.map((endpoint) => request(endpoint.path, endpoint.method, endpoint.body)),
)
assert.deepEqual(history(after), history(before), 'report data unchanged apart from current status')
assert.equal(after[0].peerEvaluationAverages.find((row) => row.studentId === 4).enabled, false)
assert.ok(after[1].length > 0 && after[1].every((row) => row.enabled === false))
assert.ok(
  after[2].content.length > 0 && after[2].content.every((row) => row.studentEnabled === false),
)
const detailsAfter = await request('/evaluations/students/4/week/2023-W31/details')
const submittedAfter = await request('/evaluations/evaluators/4/week/2023-W31')
assert.deepEqual(history(detailsAfter), history(detailsBefore))
assert.deepEqual(history(submittedAfter), history(submittedBefore))
assert.ok(detailsAfter.length > 0 && detailsAfter.every((row) => row.evaluateeEnabled === false))
assert.ok(
  submittedAfter.length > 0 && submittedAfter.every((row) => row.evaluatorEnabled === false),
)
const revokedRamWrite = await fetch(`${base}/teams/${student.teamId}/documents`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${studentToken}`, 'Content-Type': 'application/json' },
  body: '{}',
})
assert.equal(revokedRamWrite.status, 401)
assert.deepEqual(
  await request(`/teams/${student.teamId}/documents/search`, 'POST', {}),
  ramBefore,
  'rejected RAM write leaves documents unchanged',
)
const section = await request(`/sections/${student.sectionId}`)
let futureWeek
for (const candidate of section.activeWeeks.filter((week) => week > '2023-W33')) {
  const received = await request(`/evaluations/students/4/week/${candidate}/details`)
  const submitted = await request(`/evaluations/evaluators/4/week/${candidate}`)
  if (!received.length && !submitted.length) {
    futureWeek = candidate
    break
  }
}
assert.ok(futureWeek, 'future active week without historical participation')
const futureSection = await request(`/evaluations/sections/${student.sectionId}/week/${futureWeek}`)
assert.ok(futureSection.peerEvaluationAverages.every((row) => row.studentId !== 4))
assert.deepEqual(
  await request(`/evaluations/students/4?startWeek=${futureWeek}&endWeek=${futureWeek}`),
  [],
)
for (let repeat = 0; repeat < 5; repeat++)
  for (const endpoint of endpoints) await request(endpoint.path, endpoint.method, endpoint.body)
const runs = []
for (const concurrency of [1, 75, 100]) {
  const perEndpoint = concurrency === 1 ? 20 : 100
  const jobs = Array.from(
    { length: perEndpoint * endpoints.length },
    (_, index) => endpoints[index % endpoints.length],
  )
  const samples = new Map(endpoints.map((endpoint) => [endpoint.name, []]))
  const errors = []
  let cursor = 0
  const started = performance.now()
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      while (cursor < jobs.length) {
        const endpoint = jobs[cursor++]
        const start = performance.now()
        try {
          await request(endpoint.path, endpoint.method, endpoint.body)
        } catch (error) {
          errors.push({ endpoint: endpoint.name, error: error.message })
        }
        samples.get(endpoint.name).push(performance.now() - start)
      }
    }),
  )
  const results = [...samples.entries()].map(([endpoint, values]) => {
    values.sort((a, b) => a - b)
    const rounded = (value) => Math.round(value * 100) / 100
    return {
      endpoint,
      samples: values.length,
      p50Ms: rounded(values[Math.ceil(values.length * 0.5) - 1]),
      p95Ms: rounded(values[Math.ceil(values.length * 0.95) - 1]),
      maxMs: rounded(values.at(-1)),
    }
  })
  runs.push({ concurrency, durationMs: Math.round(performance.now() - started), results, errors })
  console.log(JSON.stringify(runs.at(-1)))
}
await writeFile(
  output,
  JSON.stringify(
    {
      measuredAt: new Date().toISOString(),
      node: process.version,
      fixture: {
        sectionId: student.sectionId,
        reportStudents: after[0].peerEvaluationAverages.length,
        warRows: after[2].totalElements,
        emptyFutureWeek: futureWeek,
      },
      runtimeChecks: [
        'Current evaluator/evaluatee/WAR/average status flags after real deactivation',
        'Unchanged historical scores, averages, comments and activity values',
        'Revoked student RAM write returns 401 and document search results remain unchanged',
        'No empty future participation in section or period evaluation reports',
      ],
      limitations: [
        'HTTP response + JSON parse only; no browser render measurements',
        'Seeded dev dataset, not a representative 70-student cohort',
        'One real instructor JWT shared by workers, not 75 distinct users',
        'No RAM editing workload or 100 actual concurrent editors',
        'Local Windows JVM + Docker Desktop MySQL; not deployment hardware',
      ],
      runs,
    },
    null,
    2,
  ),
)
assert.ok(
  runs.every((run) => run.errors.length === 0),
  'all sampled requests succeed',
)
