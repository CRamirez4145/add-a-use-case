// UC-STU-deactivate-student T1/T4/T7/T13/T23 and corrected T24/T25 UI boundary.
// API responses are stubbed here; real authentication and permission isolation are verified by
// StudentLifecycleAccessIntegrationTest against MySQL and the actual security filter chain.
const student = {
  id: 4,
  username: 'student@example.test',
  firstName: 'John',
  lastName: 'Smith',
  email: 'student@example.test',
  enabled: true,
  roles: 'student',
  sectionId: 2,
  sectionName: 'Target course section',
  teamId: 1,
  teamName: 'Team One'
}
const result = (data, message = 'Find successfully') => ({ flag: true, code: 200, message, data })
const section = (id) => ({
  sectionId: id,
  sectionName: 'Course section',
  rubricId: id === 2 ? 1 : 88,
  startDate: '2023-07-31',
  endDate: '2024-10-06',
  activeWeeks: ['2023-W32']
})
const callers = [
  { name: 'assigned instructor', roles: 'instructor', defaultSectionId: 2 },
  {
    name: 'unassigned owning admin with another default',
    roles: 'admin instructor',
    defaultSectionId: 99
  },
  {
    name: 'unassigned owning admin with no default',
    roles: 'admin instructor',
    defaultSectionId: null
  }
]

function visitDetails(caller = callers[1], commandResponse, detailsResponse) {
  cy.intercept('GET', '**/api/v1/students/4', detailsResponse || result(student)).as('details')
  cy.intercept(
    'POST',
    '**/api/v1/sections/*/students/*/deactivation',
    commandResponse || {
      delay: 600,
      body: result(
        { outcome: 'DEACTIVATED', student: { ...student, enabled: false } },
        'Student has been deactivated.'
      )
    }
  ).as('deactivation')
  cy.intercept('PUT', '**/api/v1/students/*', { statusCode: 500 }).as('profileSave')
  cy.intercept('DELETE', '**/api/v1/students/*', { statusCode: 500 }).as('studentDelete')
  cy.intercept(
    'POST',
    '**/api/v1/activities/search*',
    result({
      content: [],
      number: 0,
      size: 50,
      totalElements: 0
    })
  ).as('activityReports')
  cy.intercept('GET', '**/api/v1/evaluations/students/*', result([])).as('evaluationReports')
  cy.intercept('GET', '**/api/v1/sections/*', (req) => {
    req.reply(result(section(Number(new URL(req.url).pathname.split('/').pop()))))
  }).as('sections')
  cy.intercept('GET', '**/api/v1/rubrics/*', result({ rubricId: 1, criteria: [] })).as('rubrics')
  cy.visit('/students/4', {
    onBeforeLoad(win) {
      // UI-only JWT fixture: route guards decode expiry; stubbed API calls do not authenticate it.
      const token = `${btoa('{"alg":"none"}')}.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.fixture`
      win.localStorage.setItem('token', JSON.stringify({ token }))
      win.localStorage.setItem(
        'userInfo',
        JSON.stringify({
          userInfo: {
            id: 1,
            username: 'instructor@example.test',
            firstName: 'Instructor',
            lastName: 'Example',
            email: 'instructor@example.test',
            roles: caller.roles,
            defaultSectionId: caller.defaultSectionId
          }
        })
      )
      win.localStorage.setItem(
        'settings',
        JSON.stringify({ defaultSectionId: caller.defaultSectionId, defaultCourseId: 1 })
      )
    }
  })
  cy.wait('@details')
  if (!detailsResponse) {
    cy.get('[data-cy=student-details]')
      .should('contain', 'John Smith')
      .and('contain', 'Target course section')
  }
}
function noAutomaticReportsOrMutations() {
  for (const alias of [
    'activityReports',
    'evaluationReports',
    'sections',
    'rubrics',
    'profileSave',
    'studentDelete'
  ]) {
    cy.get(`@${alias}.all`).should('have.length', 0)
  }
}
function openWarning() {
  cy.get('[data-cy=deactivation-open]').click()
  cy.get('[data-cy=deactivation-dialog]').should('be.visible')
}

describe('Student deactivation and scoped details', () => {
  callers.forEach((caller) => {
    it(`completes the actual-course-section lifecycle for ${caller.name} (T1/T25)`, () => {
      visitDetails(caller)
      noAutomaticReportsOrMutations()
      openWarning()
      for (const consequence of [
        'immediately lose access',
        'existing token',
        'Team membership',
        'weekly activity reports',
        'submitted and received peer evaluations',
        'remain unchanged',
        'no longer owe',
        'Active teammates',
        'edits targeting this student will be blocked',
        'reactivated in the future',
        'does not delete'
      ]) {
        cy.get('[data-cy=deactivation-warning]').should('contain', consequence)
      }
      cy.get('[data-cy=deactivation-confirm]').click()
      cy.wait('@deactivation')
        .its('request.url')
        .should('match', /\/sections\/2\/students\/4\/deactivation$/)
      cy.get('.el-message--success').should('contain', 'Student has been deactivated.')
      cy.get('[data-cy=student-status]').should('contain', 'Deactivated')
      cy.get('@deactivation.all').should('have.length', 1)
      noAutomaticReportsOrMutations()
      cy.location('pathname').should('eq', '/students/4')
      cy.window().then((win) =>
        expect(JSON.parse(win.localStorage.getItem('settings')).defaultSectionId).to.eq(
          caller.defaultSectionId
        )
      )
    })

    for (const dismissal of ['Cancel', 'close', 'Escape', 'backdrop']) {
      it(`${dismissal} leaves ${caller.name}'s details unchanged (T4/T25)`, () => {
        visitDetails(caller)
        openWarning()
        if (dismissal === 'Cancel') cy.get('[data-cy=deactivation-cancel]').click()
        if (dismissal === 'close') cy.get('.el-dialog__headerbtn').click()
        if (dismissal === 'Escape') cy.get('body').type('{esc}')
        if (dismissal === 'backdrop') cy.get('.el-overlay-dialog').click(5, 5, { force: true })
        cy.get('[data-cy=deactivation-dialog]').should('not.be.visible')
        cy.get('[data-cy=student-status]').should('contain', 'Active')
        cy.get('@deactivation.all').should('have.length', 0)
        cy.get('.el-message--success').should('not.exist')
        noAutomaticReportsOrMutations()
      })
    }
  })

  it('submits once and blocks dismissal while the confirmed request is pending (T13)', () => {
    visitDetails(callers[1], {
      delay: 1500,
      body: result(
        { outcome: 'DEACTIVATED', student: { ...student, enabled: false } },
        'Student has been deactivated.'
      )
    })
    openWarning()
    cy.get('[data-cy=deactivation-confirm]').click().should('be.disabled').click({ force: true })
    cy.get('[data-cy=deactivation-cancel]').should('be.disabled')
    cy.get('body').type('{esc}')
    cy.get('[data-cy=deactivation-dialog]').should('be.visible')
    cy.wait('@deactivation')
    cy.get('@deactivation.all').should('have.length', 1)
  })

  it('shows the no-op outcome as information without saving the profile (T7)', () => {
    visitDetails(callers[2], {
      body: result(
        { outcome: 'ALREADY_DEACTIVATED', student: { ...student, enabled: false } },
        'Student is already deactivated'
      )
    })
    openWarning()
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@deactivation')
    cy.get('.el-message--info').should('contain', 'Student is already deactivated')
    cy.get('.el-message--success').should('not.exist')
    cy.get('[data-cy=student-status]').should('contain', 'Deactivated')
    cy.get('@details.all').should('have.length', 1)
    noAutomaticReportsOrMutations()
  })

  it('loads reports only after an explicit request and uses the actual course section for criteria (T25)', () => {
    visitDetails({ ...callers[0], defaultSectionId: 99 })
    noAutomaticReportsOrMutations()
    // The different preference is present before Pinia initializes, not merely changed in localStorage.
    cy.get('[data-cy=show-performance-reports]').click()
    cy.wait('@activityReports')
    cy.wait('@evaluationReports')
    cy.wait('@rubrics')
      .its('request.url')
      .should('match', /\/rubrics\/1$/)
    cy.get('@sections.all').then((calls) =>
      expect(calls.some((call) => /\/sections\/2$/.test(call.request.url))).to.eq(true)
    )
    cy.get('@deactivation.all').should('have.length', 0)
  })

  for (const error of [
    { status: 403, message: 'You are not authorized to deactivate this student.' },
    { status: 404, message: 'Student no longer exists' }
  ]) {
    it(`preserves unrelated GET ${error.status} feedback and navigation`, () => {
      const message = 'Unrelated student-details read failed'
      visitDetails(callers[0], undefined, {
        statusCode: error.status,
        body: { flag: false, code: error.status, message, data: null }
      })
      cy.get('.el-message--error').should('contain', message)
      cy.get('h1').should('have.text', String(error.status))
      cy.get('[class*="deactivation-error-feedback-"]').should('not.exist')
      cy.get('@deactivation.all').should('have.length', 0)
    })

    it(`shows exact ${error.status} feedback without success (T23)`, () => {
      const navigationSnapshots = []
      visitDetails(callers[1], {
        statusCode: error.status,
        body: { flag: false, code: error.status, message: error.message, data: null }
      })
      openWarning()
      cy.window().then((win) => {
        const originalPushState = win.history.pushState
        cy.stub(win.history, 'pushState').callsFake(function (...args) {
          // Capture the rendered feedback before delegating to the real navigation.
          // Checking only the final page and toast would also pass if feedback appeared too late.
          const toast = win.document.querySelector('.el-message--error')
          navigationSnapshots.push({
            from: win.location.pathname,
            message: toast?.querySelector('.el-message__content')?.textContent?.trim(),
            visible: !!toast && Cypress.dom.isVisible(toast)
          })
          return originalPushState.apply(win.history, args)
        })
      })
      cy.get('[data-cy=deactivation-confirm]').click()
      cy.wait('@deactivation')
      cy.get('.el-message--error').should('contain', error.message)
      cy.get('.el-message--success').should('not.exist')
      cy.get('h1').should('have.text', String(error.status))
      cy.location('pathname').should('not.eq', '/students/4')
      cy.then(() => {
        expect(navigationSnapshots, 'one error navigation').to.have.length(1)
        expect(navigationSnapshots[0], 'visible exact feedback before URL changes').to.deep.equal({
          from: '/students/4',
          message: error.message,
          visible: true
        })
      })
    })
  }

  it('uses existing logout handling for 401 (T23)', () => {
    visitDetails(callers[1], {
      statusCode: 401,
      body: { flag: false, code: 401, message: 'Unauthenticated', data: null }
    })
    openWarning()
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@deactivation')
    cy.location('pathname').should('eq', '/login')
    cy.get('.el-message--success').should('not.exist')
  })

  it('shows no success and does not retry a network failure automatically (T23)', () => {
    visitDetails(callers[1], { forceNetworkError: true })
    cy.window().then((win) => cy.spy(win.XMLHttpRequest.prototype, 'send').as('commandSends'))
    openWarning()
    cy.get('[data-cy=deactivation-confirm]').click()
    cy.wait('@deactivation').should('have.property', 'error')
    cy.get('.el-message--error').should('be.visible')
    cy.get('[data-cy=deactivation-confirm]').should('be.enabled')
    cy.get('.el-message--success').should('not.exist')
    cy.get('[data-cy=deactivation-cancel]').click()
    // forceNetworkError can cause browser transport retries; those are not new application sends.
    cy.get('@commandSends').should('have.been.calledOnce')
  })
})
