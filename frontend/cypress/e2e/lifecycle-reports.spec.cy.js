// UC-STU-deactivate-student T19/T20/T21. Backend tests separately verify persisted history and authorization.
const week = '2023-W32'
const result = (data) => ({ flag: true, code: 200, message: 'Handled successfully', data })
const member = (id, firstName, enabled) => ({
  id,
  firstName,
  lastName: 'Student',
  enabled,
  username: `${firstName}@example.test`,
  email: `${firstName}@example.test`,
  roles: 'student',
  sectionId: 2,
  sectionName: 'Actual course section',
  teamId: 1,
  teamName: 'Team One'
})
const john = member(4, 'John', true),
  eric = member(5, 'Eric', false),
  jerry = member(6, 'Jerry', true),
  woody = member(7, 'Woody', false)
const evaluation = (target, id) => ({
  evaluationId: id,
  week,
  evaluatorId: 4,
  evaluatorName: 'John Student',
  evaluateeId: target.id,
  evaluateeName: `${target.firstName} Student`,
  evaluatorEnabled: true,
  evaluateeEnabled: target.enabled,
  ratings: [{ criterionId: 1, criterion: 'Quality', actualScore: 8 }],
  totalScore: 8,
  publicComment: 'Retained public',
  privateComment: 'Retained private'
})
const average = {
  studentId: 5,
  firstName: 'Eric',
  lastName: 'Student',
  email: eric.email,
  enabled: false,
  week,
  teamName: 'Team One',
  averageTotalScore: 51.25,
  publicComments: ['Retained public'],
  privateComments: ['Retained private'],
  ratingAverages: [{ criterionId: 1, averageScore: 8 }]
}
const activity = {
  activityId: 21,
  studentId: 5,
  studentName: 'Eric Student',
  studentEnabled: false,
  teamId: 1,
  teamName: 'Team One',
  week,
  category: 'DEVELOPMENT',
  activity: 'Retained work',
  description: 'Retained description',
  plannedHours: 4,
  actualHours: 3,
  status: 'COMPLETED',
  comments: 'Retained activity comment'
}

function setup() {
  cy.intercept(
    'GET',
    '**/api/v1/sections/2',
    result({
      sectionId: 2,
      rubricId: 1,
      sectionName: 'Actual course section',
      startDate: '2023-07-31',
      endDate: '2027-10-06',
      activeWeeks: [week]
    })
  ).as('section')
  cy.intercept(
    'GET',
    '**/api/v1/rubrics/1',
    result({
      criteria: [{ criterionId: 1, criterion: 'Quality', description: 'Quality', maxScore: 10 }]
    })
  )
  cy.intercept('GET', '**/api/v1/students/teams/1', result([john, eric, jerry, woody]))
  cy.intercept('GET', '**/api/v1/students/5', result(eric))
  cy.intercept(
    'GET',
    '**/api/v1/evaluations/evaluators/4/week/*',
    result([evaluation(john, 11), evaluation(eric, 12)])
  )
  cy.intercept('PUT', '**/api/v1/evaluations/*', (req) => req.reply(result(req.body))).as('update')
  cy.intercept('POST', '**/api/v1/evaluations', (req) =>
    req.reply(result({ ...req.body, evaluationId: 100 + req.body.evaluateeId }))
  ).as('create')
  cy.intercept('POST', '**/api/v1/evaluations/weeks/*/receipt', result(null)).as('receipt')
  cy.intercept(
    'GET',
    '**/api/v1/evaluations/students/*/week/*/details',
    result([
      { ...evaluation(eric, 12), evaluatorName: 'Deactivated evaluator', evaluatorEnabled: false }
    ])
  ).as('details')
  cy.intercept('GET', '**/api/v1/evaluations/students/*?*', result([average])).as('averages')
  cy.intercept(
    'GET',
    '**/api/v1/evaluations/sections/2/week/*',
    result({ sectionName: 'Actual course section', week, peerEvaluationAverages: [average] })
  ).as('report')
  cy.intercept(
    'GET',
    '**/api/v1/sections/2/submission-status*',
    result({ teams: [], weekActive: true })
  )
  cy.intercept(
    'POST',
    '**/api/v1/activities/search*',
    result({ content: [activity], number: 0, size: 50, totalElements: 1 })
  ).as('activities')
  cy.intercept(
    'POST',
    '**/api/v1/teams/search*',
    result({ content: [{ teamId: 1, teamName: 'Team One', students: [john, eric, jerry] }] })
  )
}
function visit(path, roles = 'admin instructor') {
  cy.clock(Date.UTC(2023, 7, 20, 12), ['Date'])
  cy.visit(path, {
    onBeforeLoad(win) {
      const token = `${btoa('{"alg":"none"}')}.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.fixture`
      win.localStorage.setItem('token', JSON.stringify({ token }))
      win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: { ...john, roles } }))
      win.localStorage.setItem(
        'settings',
        JSON.stringify({ defaultSectionId: 2, defaultCourseId: 1 })
      )
    }
  })
}

describe('Lifecycle-aware evaluation submission', () => {
  beforeEach(setup)
  it('updates/creates per active evaluatee and retains inactive history read-only (T19)', () => {
    visit('/evaluations/submit-evaluations', 'student')
    cy.contains('.el-table__row', 'Eric Student').within(() => {
      cy.contains('Deactivated').should('be.visible')
      cy.get('textarea').should('be.disabled')
      cy.get('.el-input-number').should('not.exist')
      cy.get('textarea').eq(0).should('have.value', 'Retained public')
      cy.get('textarea').eq(1).should('have.value', 'Retained private')
    })
    cy.contains('.el-table__row', 'Woody Student').should('not.exist')
    cy.get('[data-cy=evaluation-save]').click()
    cy.wait('@create').its('request.body.evaluateeId').should('eq', 6)
    cy.wait('@update')
      .its('request.url')
      .should('match', /\/evaluations\/11$/)
    cy.wait('@receipt')
    cy.get('@create.all').should('have.length', 1)
    cy.get('@update.all').should('have.length', 1)
    cy.contains('.el-table__row', 'Eric Student')
      .find('textarea')
      .first()
      .should('have.value', 'Retained public')
  })

  it('never submits when all retained rows are inactive (T19)', () => {
    cy.intercept('GET', '**/api/v1/students/teams/1', result([eric, woody]))
    cy.intercept('GET', '**/api/v1/evaluations/evaluators/4/week/*', result([evaluation(eric, 12)]))
    visit('/evaluations/submit-evaluations', 'student')
    cy.contains('Eric Student').should('be.visible')
    cy.get('[data-cy=evaluation-save]').should('be.disabled')
    cy.get('@create.all').should('have.length', 0)
    cy.get('@update.all').should('have.length', 0)
  })

  it('retains successful new ids after a partial failure and retries only on explicit action (T19)', () => {
    cy.intercept(
      'GET',
      '**/api/v1/students/teams/1',
      result([john, eric, jerry, member(8, 'Amanda', true)])
    )
    let failAmanda = true
    cy.intercept('POST', '**/api/v1/evaluations', (req) => {
      if (req.body.evaluateeId === 8 && failAmanda) {
        failAmanda = false
        req.reply({
          statusCode: 500,
          body: { flag: false, code: 500, message: 'Temporary failure', data: null }
        })
      } else
        req.reply({
          delay: 500,
          body: result({ ...req.body, evaluationId: 100 + req.body.evaluateeId })
        })
    }).as('create')
    visit('/evaluations/submit-evaluations', 'student')
    cy.get('[data-cy=evaluation-save]').click()
    cy.contains('.el-message--error', 'Temporary failure').should('be.visible')
    cy.get('[data-cy=evaluation-save]').should('be.enabled')
    cy.get('@create.all').should('have.length', 2)
    cy.get('[data-cy=evaluation-save]').click()
    cy.wait('@receipt')
    cy.get('@create.all').then((calls) => {
      expect(calls.filter((call) => call.request.body.evaluateeId === 6)).to.have.length(1)
      expect(calls.filter((call) => call.request.body.evaluateeId === 8)).to.have.length(2)
    })
    cy.get('@update.all').then((calls) => {
      expect(calls.some((call) => /\/evaluations\/106$/.test(call.request.url))).to.eq(true)
      expect(calls.some((call) => /\/evaluations\/12$/.test(call.request.url))).to.eq(false)
    })
  })
})

describe('Historical account-status labels without changing report values', () => {
  beforeEach(setup)
  it('retains section evaluation scores/comments and marks inactive evaluators (T20)', () => {
    visit('/section-evaluations')
    cy.wait('@report')
    cy.contains('.el-table__row', 'Eric')
      .should('contain', 'Deactivated')
      .and('contain', '51.25')
      .and('contain', 'Retained public')
      .and('contain', 'Retained private')
    cy.contains('.el-table__row', 'Eric').find('button').click()
    cy.wait('@details')
    cy.get('.el-dialog').should('contain', 'Deactivated evaluator')
    cy.get('.el-dialog .el-tag').should('contain', 'Deactivated')
  })
  it('retains student WAR and evaluation values in deferred dashboard reports (T20/T21)', () => {
    visit('/students/5')
    cy.get('[data-cy=show-performance-reports]').click()
    cy.wait('@activities')
    cy.wait('@averages')
    cy.contains('Retained work').scrollIntoView().should('be.visible')
    cy.contains('Retained public').scrollIntoView().should('be.visible')
    cy.contains('51.25').scrollIntoView().should('be.visible')
    cy.get('.el-tag').filter(':contains("Deactivated")').should('have.length.at.least', 3)
  })
  it('keeps the student self-report free of private comments (T20)', () => {
    cy.intercept(
      'GET',
      '**/api/v1/evaluations/students/*?*',
      result([
        {
          ...average,
          studentId: john.id,
          firstName: john.firstName,
          email: john.email,
          enabled: true,
          privateComments: null
        }
      ])
    ).as('selfAverages')
    visit('/evaluations/my-evaluations', 'student')
    cy.contains('button', 'Query').click()
    cy.wait('@selfAverages')
    cy.contains('Retained public').scrollIntoView().should('be.visible')
    cy.contains('Retained private').should('not.exist')
    cy.contains('51.25').scrollIntoView().should('be.visible')
  })
  it('labels retained team WAR activity and preserves planned/actual hours (T21)', () => {
    visit('/activities/team-activities', 'student')
    cy.wait('@activities')
    cy.contains('.el-table__row', 'Retained work')
      .should('contain', 'Deactivated')
      .and('contain', '4')
      .and('contain', '3')
  })
  it('labels retained course-section WAR rows (T21)', () => {
    visit('/section-activities')
    cy.wait('@activities')
    cy.get('.el-table__expand-icon')
      .first()
      .then(($icon) => {
        if (!$icon.hasClass('el-table__expand-icon--expanded')) cy.wrap($icon).click()
      })
    cy.contains('.el-table__row', 'Retained work')
      .should('contain', 'Deactivated')
      .and('contain', '4')
      .and('contain', '3')
    cy.contains('.el-table__row', 'Retained work').find('button').click()
    cy.wait('@details')
    cy.get('.el-dialog').should('contain', 'Deactivated evaluator')
    cy.get('.el-dialog .el-tag').should('contain', 'Deactivated')
  })
})
