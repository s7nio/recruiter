package de.th.wildau.recruiter.ejb.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ejb.LocalBean;
import javax.ejb.Remote;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.transaction.Transactional;
import javax.validation.ConstraintViolationException;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import de.th.wildau.recruiter.ejb.BusinessError;
import de.th.wildau.recruiter.ejb.BusinessException;
import de.th.wildau.recruiter.ejb.RoleName;
import de.th.wildau.recruiter.ejb.model.Address;
import de.th.wildau.recruiter.ejb.model.Role;
import de.th.wildau.recruiter.ejb.model.User;
import de.th.wildau.recruiter.ejb.service.remote.UserServiceRemote;

@Stateless
@LocalBean
@Remote(UserServiceRemote.class)
@PermitAll
public class UserService extends Crud {

	/** TOP SECRET application key (salt & papper) */
	private static final String PAPPER = "oJizjnhXGuQsRilM";

	private static final String PASSWORD_PATTERN = "((?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[-_:;,@#$%]).{8,20})";

	/**
	 * Generate the registration/activation mail.
	 * 
	 * @param user
	 *            account
	 * @param address
	 *            information (first- and last name required)
	 * @return String email text
	 */
	private static String getRegMailText(final String name, final String email,
			final String activationKey) {
		final StringBuilder sb = new StringBuilder("Hello ");
		sb.append(name).append(",").append(MailService.LINE_BREAK)
				.append(MailService.LINE_BREAK);
		sb.append("Thank you for the confidence and registration.").append(
				MailService.LINE_BREAK);
		sb.append("Please activate your account with the following link: ");
		sb.append("https://127.0.0.1:8443/recruiter/public/activate.jsf?email=")
				.append(email).append("&key=").append(activationKey)
				.append(" ").append(MailService.LINE_BREAK);
		sb.append(MailService.LINE_BREAK).append("Best regards,")
				.append(MailService.LINE_BREAK).append("Your recruiter team");
		return sb.toString();
	}

	private final Logger log = LoggerFactory.getLogger(UserService.class);

	@Inject
	private MailService mailService;

	@PersistenceContext
	protected EntityManager em;

	/**
	 * Activate user account with email address and activation key.
	 * 
	 * @param email
	 *            email string
	 * @param key
	 *            password
	 * @return true or throw a BusinessError
	 */
	public boolean activate(final String email, final String key)
			throws BusinessException {
		this.log.debug("activate user account (" + email + ")");
		final User u = findUser(email);
		if (u == null || StringUtils.isEmpty(u.getActivationKey())
				|| !u.getActivationKey().equals(key)) {
			throw new BusinessException(
					BusinessError.ACCOUNT_COULD_NOT_ACTIVATE);
		}
		u.setActivationKey(null);
		this.em.merge(u);
		return true;
	}

	/**
	 * Reset signin attempts. Allowed only for role admin, company and user.
	 * 
	 * @param email
	 */
	@RolesAllowed({ "ADMIN", "COMPANY", "USER" })
	public void authenticatePost(final String email) {
		signinAttemptsReset(email.toLowerCase());
	}

	/**
	 * Handle signin attempts before signin
	 * 
	 * @param email
	 * @throws BusinessException
	 *             in case of to much attempts
	 */
	public void authenticatePre(final String email) throws BusinessException {
		final User u = getUser(email.toLowerCase());
		if (u != null) {
			// check activation
			if (StringUtils.isNotBlank(u.getActivationKey())) {
				throw new BusinessException(BusinessError.ACCOUNT_NOT_ACTIVE);
			}
			// check signin attempts
			if (u.getSigninAttempts() > 10) {
				// reset activation key
				u.setActivationKey(hashSha(u.getEmail() + getRandom()));
				merge(u);
				this.mailService.send(
						"Reset activation key",
						getRegMailText(u.getAddress().getName(), u.getEmail(),
								u.getActivationKey()), u.getEmail());
				throw new BusinessException(
						BusinessError.SIGNIN_RESET_ACTIVATION_KEY);
			}

			// signin attempts +1
			final Integer newCount = u.getSigninAttempts() + 1;
			u.setSigninAttempts(newCount);
			merge(u);
		}
	}

	/**
	 * Change the current password from a user.
	 * 
	 * @param email
	 * @return String token
	 * @throws BusinessException
	 */
	@RolesAllowed({ "ADMIN", "COMPANY", "USER" })
	public void changeEmail(final String password, final String oldEmail,
			final String newEmail) throws BusinessException {
		final User u = getUser(oldEmail.toLowerCase());
		isMy(u.getId());

		// check old password
		final String dbPW = u.getPassword();
		final String checkPW = hashPassword(password + u.getPasswordSalt());
		if (!dbPW.equals(checkPW)) {
			throw new BusinessException(BusinessError.INVALID_PASSWORD);
		}
		// change email
		u.setEmail(newEmail.toLowerCase());
		merge(u);
		this.mailService.send("Your recruter email has changed",
				"Hello, your email address has been changed to " + u.getEmail()
						+ ".", oldEmail);
	}

	/**
	 * Change the current password from a user.
	 * 
	 * @param email
	 * @return String token
	 * @throws BusinessException
	 */
	@RolesAllowed({ "ADMIN", "COMPANY", "USER" })
	public void changePassword(final String email, final String oldPassword,
			final String newPassword) throws BusinessException {
		// https://stackoverflow.com/questions/2734367/implement-password-recovery-best-practice
		final User u = getUser(email.toLowerCase());
		isMy(u.getId());

		// check old password
		final String dbPW = u.getPassword();
		final String checkPW = hashPassword(oldPassword + u.getPasswordSalt());
		if (!dbPW.equals(checkPW)) {
			throw new BusinessException(BusinessError.INVALID_PASSWORD);
		}
		// check new pw
		validatePassword(newPassword);
		// change password
		u.setPasswordSalt(getRandom());
		u.setPassword(hashPassword(newPassword + u.getPasswordSalt()));
		merge(u);
		this.em.flush();
		this.mailService.send("Your recruter password has changed",
				"Hello, your password has been changed.", u.getEmail());
	}

	/**
	 * Delete a user profile/account.
	 * 
	 * @param userId
	 * @throws BusinessException
	 */
	@RolesAllowed({ "ADMIN" })
	public void delete(final Long userId) throws BusinessException {
		final User u = findById(User.class, userId);
		if (u == null) {
			throw new BusinessException(BusinessError.ACCOUNT_NOT_ACTIVE);
		}
		delete(u);
	}

	/**
	 * Equals to {@code #getUser(String)}, but return {@code null} if none
	 * entity found.
	 * 
	 * @param email
	 * @return {@code User} or {@code null}
	 */
	public User findUser(final String email) {
		try {
			return getUser(email);
		} catch (final NoResultException e) {
			return null;
		}
	}

	/**
	 * Get the address from a user.
	 * 
	 * @param email
	 * @return address or null
	 */
	@RolesAllowed({ "ADMIN", "COMPANY", "USER" })
	public Address getAddress(final String email) {
		final Address address = findUser(email).getAddress();
		if (address == null) {
			return null;
		}
		address.getName();
		return address;
	}

	/**
	 * Get current user.
	 * 
	 * @return User or {@code null}
	 */
	public User getCurrentUser() {
		return findUser(getCurrentUserId());
	}

	/**
	 * Find salt for a specific user.
	 * 
	 * @param email
	 * @return String salt, if email not existing sha265 of email string
	 */
	public String getPasswordSalt(final String email) {
		try {
			final CriteriaBuilder cb = this.em.getCriteriaBuilder();
			final CriteriaQuery<User> cq = cb.createQuery(User.class);
			final Root<User> r = cq.from(User.class);
			cq.select(r);
			cq.where(cb.equal(r.get("email"), email.toLowerCase()));
			final TypedQuery<User> q = this.em.createQuery(cq);
			return q.getSingleResult().getPasswordSalt();
		} catch (final NoResultException e) {
			this.log.error("can not find password salt for email " + email);
			return hashSha(email.toLowerCase());
		}
	}

	/**
	 * Get a specific role.
	 * 
	 * @param roleName
	 * @return Role
	 */
	public Role getRole(final RoleName role) {
		final CriteriaBuilder cb = this.em.getCriteriaBuilder();
		final CriteriaQuery<Role> cq = cb.createQuery(Role.class);
		final Root<Role> r = cq.from(Role.class);
		cq.where(cb.equal(r.get("name"), role));
		return this.em.createQuery(cq).getSingleResult();
	}

	/**
	 * Get all user roles (without admin).
	 * 
	 * @return List of roles
	 */
	public List<Role> getRoles() {
		final CriteriaBuilder cb = this.em.getCriteriaBuilder();
		final CriteriaQuery<Role> cq = cb.createQuery(Role.class);
		final Root<Role> r = cq.from(Role.class);
		cq.orderBy(cb.asc(r.get("name")));
		final List<Role> res = this.em.createQuery(cq).getResultList();
		return res.stream().filter(x -> x.getName() != RoleName.ADMIN)
				.collect(Collectors.toList());
	}

	/**
	 * Get user by email address.
	 * 
	 * @param email
	 * @throws NoResultException
	 *             if no entity found
	 * @return User or null if user doesn't exist.
	 */
	public User getUser(final String email, final String... fetch) {
		final CriteriaBuilder cb = this.em.getCriteriaBuilder();
		final CriteriaQuery<User> cq = cb.createQuery(User.class);
		final Root<User> r = cq.from(User.class);
		for (final String item : fetch) {
			r.fetch(item);
		}
		cq.select(r).where(cb.equal(r.get("email"), email.toLowerCase()));
		try {
			return this.em.createQuery(cq).getSingleResult();
		} catch (final NoResultException e) {
			return null;
		}
	}

	/**
	 * Get all users.
	 * 
	 * @return List of users.
	 */
	@RolesAllowed({ "ADMIN" })
	public List<User> getUsers() {
		final CriteriaBuilder cb = this.em.getCriteriaBuilder();
		final CriteriaQuery<User> cq = cb.createQuery(User.class);
		final Root<User> r = cq.from(User.class);
		cq.orderBy(cb.asc(r.get("email")));
		final List<User> res = this.em.createQuery(cq).getResultList();
		// res.stream().forEach(x -> x.getRoles().size());
		for (final User i : res) {
			for (final Role j : i.getRoles()) {
				j.getName();
			}
		}
		return res;
	}

	/**
	 * Register a new user.
	 */
	@Transactional
	public void register(final String email, final String password,
			final RoleName roleName, final User user, final Address address)
			throws BusinessException {
		// prepare
		user.setEmail(email);
		user.setAddress(address);
		user.setPasswordSalt(getRandom());
		user.setPassword(hashPassword(password + user.getPasswordSalt()));
		user.setActivationKey(hashSha(user.getEmail() + getRandom()));

		if (findUser(user.getEmail()) != null) {
			throw new BusinessException(BusinessError.USER_ALREADY_EXIST);
		}
		try {
			save(user);
			this.mailService.send(
					"Activate recruter account",
					getRegMailText(user.getAddress().getName(),
							user.getEmail(), user.getActivationKey()), user
							.getEmail());
		} catch (final ConstraintViolationException e) {
			this.log.error(e.getMessage());
		}
	}

	/**
	 * Reset a password from a user, which are forgot her sign in.
	 * 
	 * @param email
	 * @param token
	 * @param newPassword
	 */
	@PermitAll
	public void resetPassword(final String email, final String token,
			final String newPassword) {
		// TODO implement
	}

	/**
	 * Reset a password by tokens. Required method call
	 * {@link #resetPasswordTokens(String)}.
	 * 
	 * @param email
	 * @param key
	 * @param token
	 */
	public void resetPasswordToken(final String email, final String key,
			final String token) {
		// TODO implement
	}

	/**
	 * Update the current user profile.
	 * 
	 * @param user
	 *            with new addess
	 * @throws BusinessException
	 */
	public void updateProfile(final User user) throws BusinessException {
		isMy(user.getId());
		final User u = getUser(user.getEmail(), "address");
		final String oldEmail = u.getEmail();
		u.setAddress(user.getAddress());
		merge(u.getAddress());
		merge(u);
		this.mailService.send("Your recruter profile changed",
				"Hello, your profile has been changed.", oldEmail);
	}

	/**
	 * Check new Password again the constrains.
	 * 
	 * @param password
	 * @throws BusinessException
	 */
	public final void validatePassword(final String password)
			throws BusinessException {
		if (!password.matches(PASSWORD_PATTERN)) {
			throw new BusinessException(BusinessError.PASSWORD_PATTERN);
		}
	}

	// @Deprecated
	// private String hashBaseSha(final String value) {
	// return Base64.getEncoder().encodeToString(hashSha(value).getBytes());
	// }

	private String getRandom() {
		return RandomStringUtils.randomAlphanumeric(32);
	}

	private String hashPassword(final String value) {
		try {
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(
					value.getBytes());
			return Base64.getEncoder().encodeToString(hash);
		} catch (final NoSuchAlgorithmException e) {
			e.printStackTrace();
			return "";
		}
	}

	@Deprecated
	private String hashSha(final String value) {
		return Hashing.sha256().hashString(value, Charsets.UTF_8).toString();
	}

	private void isMy(final Long userId) throws BusinessException {
		if (userId != getCurrentUser().getId()) {
			throw new BusinessException(BusinessError.THIS_IS_NOT_YOURS);
		}
	}

	/**
	 * Reset user signin attempts.
	 * 
	 * @param email
	 */
	private void signinAttemptsReset(final String email) {
		final User u = getUser(email.toLowerCase());
		if (u != null) {
			u.setSigninAttempts(0);
			merge(u);
		}
	}
}
